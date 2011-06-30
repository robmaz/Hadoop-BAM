// Copyright (c) 2010 Aalto University
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
// IN THE SOFTWARE.

package fi.tkk.ics.hadoop.bam.cli.plugins.chipster;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.FileAlreadyExistsException;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.LineReader;

import hadooptrunk.MultipleOutputs;

import net.sf.samtools.util.BlockCompressedInputStream;
import net.sf.samtools.util.BlockCompressedStreamConstants;

import fi.tkk.ics.hadoop.bam.custom.hadoop.InputSampler;
import fi.tkk.ics.hadoop.bam.custom.hadoop.TotalOrderPartitioner;
import fi.tkk.ics.hadoop.bam.custom.jargs.gnu.CmdLineParser;
import fi.tkk.ics.hadoop.bam.custom.samtools.BlockCompressedOutputStream;
import fi.tkk.ics.hadoop.bam.custom.samtools.SAMRecord;

import static fi.tkk.ics.hadoop.bam.custom.jargs.gnu.CmdLineParser.Option.*;

import fi.tkk.ics.hadoop.bam.BAMInputFormat;
import fi.tkk.ics.hadoop.bam.BAMRecordReader;
import fi.tkk.ics.hadoop.bam.cli.CLIPlugin;
import fi.tkk.ics.hadoop.bam.util.BGZFSplitFileInputFormat;
import fi.tkk.ics.hadoop.bam.util.Pair;
import fi.tkk.ics.hadoop.bam.util.Timer;
import fi.tkk.ics.hadoop.bam.util.WrapSeekable;

public final class Summarize extends CLIPlugin {
	private static final List<Pair<CmdLineParser.Option, String>> optionDescs
		= new ArrayList<Pair<CmdLineParser.Option, String>>();

	private static final CmdLineParser.Option
		sortOpt           = new BooleanOption('s', "sort"),
		outputDirOpt      = new  StringOption('o', "output-dir=PATH"),
		outputLocalDirOpt = new  StringOption('O', "output-local-dir=PATH");

	public Summarize() {
		super("summarize", "summarize BAM for zooming", "1.0",
			"WORKDIR LEVELS PATH", optionDescs,
			"Outputs, for each level in LEVELS, a summary file describing the "+
			"average number of alignments at various positions in the BAM file "+
			"in PATH. The summary files are placed in parts in WORKDIR."+
			"\n\n"+
			"LEVELS should be a comma-separated list of positive integers. "+
			"Each level is the number of alignments that are summarized into "+
			"one group.");
	}
	static {
		optionDescs.add(new Pair<CmdLineParser.Option, String>(
			outputDirOpt, "output complete summary files to the file PATH, "+
			              "removing the parts from WORKDIR"));
		optionDescs.add(new Pair<CmdLineParser.Option, String>(
			outputLocalDirOpt, "like -o, but treat PATH as referring to the "+
			                   "local FS"));
		optionDescs.add(new Pair<CmdLineParser.Option, String>(
			sortOpt, "sort created summaries by position"));
	}

	private final Timer    t = new Timer();
	private       String[] levels;
	private       Path     wrkDirPath;

	private int missingArg(String s) {
		System.err.printf("summarize :: %s not given.\n", s);
		return 3;
	}

	@Override protected int run(CmdLineParser parser) {

		final List<String> args = parser.getRemainingArgs();
		switch (args.size()) {
			case 0: return missingArg("WORKDIR");
			case 1: return missingArg("LEVELS");
			case 2: return missingArg("PATH");
			default: break;
		}

		final String wrkDir  = args.get(0),
		             bam     = args.get(2),
		             outAny  = (String)parser.getOptionValue(outputDirOpt),
		             outLoc  = (String)parser.getOptionValue(outputLocalDirOpt),
		             out;

		final boolean sort = parser.getBoolean(sortOpt);

		if (outAny != null) {
			if (outLoc != null) {
				System.err.println("summarize :: cannot accept both -o and -O!");
				return 3;
			}
			out = outAny;
		} else
			out = outLoc;

		levels = args.get(1).split(",");
		for (String l : levels) {
			try {
				int lvl = Integer.parseInt(l);
				if (lvl > 0)
					continue;
				System.err.printf(
					"summarize :: summary level '%d' is not positive!\n", lvl);
			} catch (NumberFormatException e) {
				System.err.printf(
					"summarize :: summary level '%s' is not an integer!\n", l);
			}
			return 3;
		}

		wrkDirPath = new Path(wrkDir);
		final Path    bamPath    = new Path(bam);
		final boolean forceLocal = out == outLoc;

		// Used by SummarizeOutputFormat to name the output files.
		final Configuration conf = getConf();

		conf.set(SummarizeOutputFormat.OUTPUT_NAME_PROP, bamPath.getName());

		conf.setStrings(SummarizeReducer.SUMMARY_LEVELS_PROP, levels);

		try {
			try {
				// As far as I can tell there's no non-deprecated way of getting
				// this info. We can silence this warning but not the import.
				@SuppressWarnings("deprecation")
				final int maxReduceTasks =
					new JobClient(new JobConf(conf)).getClusterStatus()
					.getMaxReduceTasks();

				conf.setInt("mapred.reduce.tasks",
				            Math.max(1, maxReduceTasks*9/10));

				if (!runSummary(bamPath))
					return 4;
			} catch (IOException e) {
				System.err.printf("summarize :: Summarizing failed: %s\n", e);
				return 4;
			}

			Path sortedTmpDir = null;
			try {
				if (sort) {
					sortedTmpDir = new Path(wrkDirPath, "sort.tmp");
					mergeOutputs(sortedTmpDir, false);

				} else if (out != null)
					mergeOutputs(new Path(out), forceLocal);

			} catch (IOException e) {
				System.err.printf("summarize :: Merging failed: %s\n", e);
				return 5;
			}

			if (sort) {
				if (!doSorting(sortedTmpDir))
					return 6;

				if (out != null) try {
					mergeOutputs(new Path(out), forceLocal);
				} catch (IOException e) {
					System.err.printf(
						"summarize :: Merging sorted output failed: %s\n", e);
					return 7;
				}
			}
		} catch (ClassNotFoundException e) { throw new RuntimeException(e); }
		  catch   (InterruptedException e) { throw new RuntimeException(e); }

		return 0;
	}

	private String getSummaryName(String lvl) {
		return getConf().get(SummarizeOutputFormat.OUTPUT_NAME_PROP)
			+ "-summary" + lvl;
	}

	private void setSamplingConf(Path input, Configuration conf)
		throws IOException
	{
		final Path inputDir =
			input.getParent().makeQualified(input.getFileSystem(conf));

		final String inputName = input.getName();

		final Path partition = new Path(inputDir, "_partitioning" + inputName);
		TotalOrderPartitioner.setPartitionFile(conf, partition);

		try {
			final URI partitionURI = new URI(
				partition.toString() + "#_partitioning" + inputName);
			DistributedCache.addCacheFile(partitionURI, conf);
			DistributedCache.createSymlink(conf);
		} catch (URISyntaxException e) { throw new RuntimeException(e); }
	}

	private boolean runSummary(Path bamPath)
		throws IOException, ClassNotFoundException, InterruptedException
	{
		final Configuration conf = getConf();
		setSamplingConf(bamPath, conf);
		final Job job = new Job(conf);

		job.setJarByClass  (Summarize.class);
		job.setMapperClass (Mapper.class);
		job.setReducerClass(SummarizeReducer.class);

		job.setMapOutputKeyClass  (LongWritable.class);
		job.setMapOutputValueClass(Range.class);
		job.setOutputKeyClass     (NullWritable.class);
		job.setOutputValueClass   (RangeCount.class);

		job.setInputFormatClass (SummarizeInputFormat.class);
		job.setOutputFormatClass(SummarizeOutputFormat.class);

		FileInputFormat .setInputPaths(job, bamPath);
		FileOutputFormat.setOutputPath(job, wrkDirPath);

		job.setPartitionerClass(TotalOrderPartitioner.class);

		System.out.println("summarize :: Sampling...");
		t.start();

		InputSampler.<LongWritable,Range>writePartitionFile(
			job, new InputSampler.SplitSampler<LongWritable,Range>(1 << 16, 10));

		System.out.printf("summarize :: Sampling complete in %d.%03d s.\n",
			               t.stopS(), t.fms());

		for (String lvl : levels)
			MultipleOutputs.addNamedOutput(
				job, "summary" + lvl, SummarizeOutputFormat.class,
				NullWritable.class, Range.class);

		job.submit();

		System.out.println("summarize :: Waiting for job completion...");
		t.start();

		if (!job.waitForCompletion(true)) {
			System.err.println("summarize :: Job failed.");
			return false;
		}

		System.out.printf("summarize :: Job complete in %d.%03d s.\n",
			               t.stopS(), t.fms());
		return true;
	}

	private void mergeOutputs(Path outPath, boolean forceLocal)
		throws IOException
	{
		System.out.println("summarize :: Merging output...");
		t.start();

		final Configuration conf = getConf();

		final FileSystem srcFS = wrkDirPath.getFileSystem(conf);
			   FileSystem dstFS =    outPath.getFileSystem(conf);

		if (forceLocal)
			dstFS = FileSystem.getLocal(conf).getRaw();

		final Timer tl = new Timer();
		for (String lvl : levels) {
			tl.start();

			final String lvlName = getSummaryName(lvl);

			final OutputStream outs = dstFS.create(new Path(outPath, lvlName));

			final FileStatus[] parts = srcFS.globStatus(new Path(
				wrkDirPath, lvlName + "-[0-9][0-9][0-9][0-9][0-9][0-9]*"));

			for (final FileStatus part : parts) {
				final InputStream ins = srcFS.open(part.getPath());
				IOUtils.copyBytes(ins, outs, conf, false);
				ins.close();
			}
			for (final FileStatus part : parts)
				srcFS.delete(part.getPath(), false);

			// Don't forget the BGZF terminator.
			outs.write(BlockCompressedStreamConstants.EMPTY_GZIP_BLOCK);
			outs.close();

			System.out.printf("summarize :: Merged level %s in %d.%03d s.\n",
				               lvl, tl.stopS(), tl.fms());
		}
		System.out.printf("summarize :: Merging complete in %d.%03d s.\n",
			               t.stopS(), t.fms());
	}

	private boolean doSorting(Path sortedTmpDir)
		throws ClassNotFoundException, InterruptedException
	{
		final Configuration conf = getConf();
		final Job[] jobs = new Job[levels.length];

		for (int i = 0; i < levels.length; ++i) {
			final String l = levels[i];
			try {
				jobs[i] = sortMerged(l, new Path(sortedTmpDir, getSummaryName(l)));
			} catch (IOException e) {
				System.err.printf("summarize :: Sorting %s failed: %s\n", l, e);
				return false;
			}
		}

		System.out.println("summarize :: Waiting for jobs' completion...");
		t.start();

		// Wait for the smaller files first, as they're likely to complete
		// sooner.
		for (int i = levels.length; i-- > 0;) {
			boolean success;
			try { success = jobs[i].waitForCompletion(true); }
			catch (IOException e) { success = false; }

			final String l = levels[i];

			if (!success) {
				System.err.printf("summarize :: Job for level %s failed.\n", l);
				return false;
			}
			System.out.printf("summarize :: Job for level %s complete.\n", l);

			final Path mergedTmp = FileInputFormat.getInputPaths(jobs[i])[0];
			try {
				mergedTmp.getFileSystem(conf).delete(mergedTmp, false);
			} catch (IOException e) {
				System.err.printf(
					"summarize :: Warning: couldn't delete '%s'\n", mergedTmp);
			}
		}
		System.out.printf("summarize :: Jobs complete in %d.%03d s.\n",
			               t.stopS(), t.fms());
		return true;
	}

	private Job sortMerged(String lvl, Path mergedTmp)
		throws IOException, ClassNotFoundException, InterruptedException
	{
		final Configuration conf = getConf();
		conf.set(SortOutputFormat.OUTPUT_NAME_PROP, mergedTmp.getName());
		setSamplingConf(mergedTmp, conf);
		final Job job = new Job(conf);

		job.setJarByClass  (Summarize.class);
		job.setMapperClass (Mapper.class);
		job.setReducerClass(SortReducer.class);

		job.setMapOutputKeyClass(LongWritable.class);
		job.setOutputKeyClass   (NullWritable.class);
		job.setOutputValueClass (Text.class);

		job.setInputFormatClass (SortInputFormat.class);
		job.setOutputFormatClass(SortOutputFormat.class);

		FileInputFormat .setInputPaths(job, mergedTmp);
		FileOutputFormat.setOutputPath(job, wrkDirPath);

		job.setPartitionerClass(TotalOrderPartitioner.class);

		System.out.printf(
			"summarize :: Sampling for sorting level %s...\n", lvl);
		t.start();

		InputSampler.<LongWritable,Text>writePartitionFile(
			job, new InputSampler.SplitSampler<LongWritable,Text>(1 << 16, 10));

		System.out.printf("summarize :: Sampling complete in %d.%03d s.\n",
			               t.stopS(), t.fms());
		job.submit();
		return job;
	}
}

final class SummarizeReducer
	extends Reducer<LongWritable,Range, NullWritable,RangeCount>
{
	public static final String SUMMARY_LEVELS_PROP = "summarize.summary.levels";

	private MultipleOutputs<NullWritable,RangeCount> mos;

	private final List<SummaryGroup> summaryGroups =
		new ArrayList<SummaryGroup>();

	private final RangeCount summary = new RangeCount();

	// This is a safe initial choice: it doesn't matter whether the first actual
	// reference ID we get matches this or not, since all summaryLists are empty
	// anyway.
	private int currentReferenceID = 0;

	@Override public void setup(
			Reducer<LongWritable,Range, NullWritable,RangeCount>.Context ctx)
	{
		mos = new MultipleOutputs<NullWritable,RangeCount>(ctx);

		for (String s : ctx.getConfiguration().getStrings(SUMMARY_LEVELS_PROP)) {
			int level = Integer.parseInt(s);
			summaryGroups.add(new SummaryGroup(level, "summary" + level));
		}
	}

	@Override protected void reduce(
			LongWritable key, Iterable<Range> ranges,
			Reducer<LongWritable,Range, NullWritable,RangeCount>.Context context)
		throws IOException, InterruptedException
	{
		final int referenceID = (int)(key.get() >>> 32);

		// When the reference sequence changes we have to flush out everything
		// we've got and start from scratch again.
		if (referenceID != currentReferenceID) {
			currentReferenceID = referenceID;
			doAllSummaries();
		}

		for (final Range range : ranges) {
			final int beg = range.beg.get(),
			          end = range.end.get();

			for (SummaryGroup group : summaryGroups) {
				group.sumBeg += beg;
				group.sumEnd += end;
				if (++group.count == group.level)
					doSummary(group);
			}
		}
	}

	@Override protected void cleanup(
			Reducer<LongWritable,Range, NullWritable,RangeCount>.Context context)
		throws IOException, InterruptedException
	{
		// Don't lose any remaining ones at the end.
		doAllSummaries();

		mos.close();
	}

	private void doAllSummaries() throws IOException, InterruptedException {
		for (SummaryGroup group : summaryGroups)
			if (group.count > 0)
				doSummary(group);
	}

	private void doSummary(SummaryGroup group)
		throws IOException, InterruptedException
	{
		summary.rid.      set(currentReferenceID);
		summary.range.beg.set((int)(group.sumBeg / group.count));
		summary.range.end.set((int)(group.sumEnd / group.count));
		summary.count.    set(group.count);
		mos.write(NullWritable.get(), summary, group.outName);

		group.reset();
	}
}

final class Range implements Writable {
	public final IntWritable beg = new IntWritable();
	public final IntWritable end = new IntWritable();

	public void setFrom(SAMRecord record) {
		beg.set(record.getAlignmentStart());
		end.set(record.getAlignmentEnd());
	}

	public int getCentreOfMass() {
		return (int)(((long)beg.get() + end.get()) / 2);
	}

	@Override public void write(DataOutput out) throws IOException {
		beg.write(out);
		end.write(out);
	}
	@Override public void readFields(DataInput in) throws IOException {
		beg.readFields(in);
		end.readFields(in);
	}
}

final class RangeCount implements Comparable<RangeCount>, Writable {
	public final Range       range = new Range();
	public final IntWritable count = new IntWritable();
	public final IntWritable rid   = new IntWritable();

	// This is what the TextOutputFormat will write. The format is
	// tabix-compatible; see http://samtools.sourceforge.net/tabix.shtml.
	//
	// It might not be sorted by range.beg though! With the centre of mass
	// approach, it most likely won't be.
	@Override public String toString() {
		return rid
		     + "\t" + range.beg
		     + "\t" + range.end
		     + "\t" + count;
	}

	// Comparisons only take into account the leftmost position.
	@Override public int compareTo(RangeCount o) {
		return Integer.valueOf(range.beg.get()).compareTo(o.range.beg.get());
	}

	@Override public void write(DataOutput out) throws IOException {
		range.write(out);
		count.write(out);
		rid  .write(out);
	}
	@Override public void readFields(DataInput in) throws IOException {
		range.readFields(in);
		count.readFields(in);
		rid  .readFields(in);
	}
}

// We want the centre of mass to be used as (the low order bits of) the key
// already at this point, because we want a total order so that we can
// meaningfully look at consecutive ranges in the reducers. If we were to set
// the final key in the mapper, the partitioner wouldn't use it.
//
// And since getting the centre of mass requires calculating the Range as well,
// we might as well get that here as well.
final class SummarizeInputFormat extends FileInputFormat<LongWritable,Range> {

	private final BAMInputFormat bamIF = new BAMInputFormat();

	@Override protected boolean isSplitable(JobContext job, Path path) {
		return bamIF.isSplitable(job, path);
	}
	@Override public List<InputSplit> getSplits(JobContext job)
		throws IOException
	{
		return bamIF.getSplits(job);
	}

	@Override public RecordReader<LongWritable,Range>
		createRecordReader(InputSplit split, TaskAttemptContext ctx)
			throws InterruptedException, IOException
	{
		final RecordReader<LongWritable,Range> rr = new SummarizeRecordReader();
		rr.initialize(split, ctx);
		return rr;
	}
}
final class SummarizeRecordReader extends RecordReader<LongWritable,Range> {

	private final BAMRecordReader bamRR = new BAMRecordReader();
	private final LongWritable    key   = new LongWritable();
	private final Range           range = new Range();

	@Override public void initialize(InputSplit spl, TaskAttemptContext ctx)
		throws IOException
	{
		bamRR.initialize(spl, ctx);
	}
	@Override public void close() throws IOException { bamRR.close(); }

	@Override public float getProgress() { return bamRR.getProgress(); }

	@Override public LongWritable getCurrentKey  () { return key; }
	@Override public Range        getCurrentValue() { return range; }

	@Override public boolean nextKeyValue() {
		SAMRecord rec;

		do {
			if (!bamRR.nextKeyValue())
				return false;

			rec = bamRR.getCurrentValue().get();
		} while (rec.getReadUnmappedFlag());

		range.setFrom(rec);
		key.set((long)rec.getReferenceIndex() << 32 | range.getCentreOfMass());
		return true;
	}
}

final class SummarizeOutputFormat
	extends TextOutputFormat<NullWritable,RangeCount>
{
	public static final String OUTPUT_NAME_PROP =
		"hadoopbam.summarize.output.name";

	@Override public RecordWriter<NullWritable,RangeCount> getRecordWriter(
			TaskAttemptContext ctx)
		throws IOException
	{
		Path path = getDefaultWorkFile(ctx, "");
		FileSystem fs = path.getFileSystem(ctx.getConfiguration());

		return new TextOutputFormat.LineRecordWriter<NullWritable,RangeCount>(
			new DataOutputStream(
				new BlockCompressedOutputStream(fs.create(path))));
	}

	@Override public Path getDefaultWorkFile(
			TaskAttemptContext context, String ext)
		throws IOException
	{
		Configuration conf = context.getConfiguration();

		// From MultipleOutputs. If we had a later version of FileOutputFormat as
		// well, we'd use getOutputName().
		String summaryName = conf.get("mapreduce.output.basename");

		// A RecordWriter is created as soon as a reduce task is started, even
		// though MultipleOutputs eventually overrides it with its own.
		//
		// To avoid creating a file called "inputfilename-null" when that
		// RecordWriter is initialized, make it a hidden file instead, like this.
		//
		// We can't use a filename we'd use later, because TextOutputFormat would
		// throw later on, as the file would already exist.
		String baseName = summaryName == null ? ".unused_" : "";

		baseName         += conf.get(OUTPUT_NAME_PROP);
		String extension  = ext.isEmpty() ? ext : "." + ext;
		int    part       = context.getTaskAttemptID().getTaskID().getId();
		return new Path(super.getDefaultWorkFile(context, ext).getParent(),
			  baseName    + "-"
			+ summaryName + "-"
			+ String.format("%06d", part)
			+ extension);
	}

	// Allow the output directory to exist.
	@Override public void checkOutputSpecs(JobContext job)
		throws FileAlreadyExistsException, IOException
	{}
}

final class SummaryGroup {
	public       int    count;
	public final int    level;
	public       long   sumBeg, sumEnd;
	public final String outName;

	public SummaryGroup(int lvl, String name) {
		level   = lvl;
		outName = name;
		reset();
	}

	public void reset() {
		sumBeg = sumEnd = 0;
		count  = 0;
	}
}

///////////////// Sorting

final class SortReducer extends Reducer<LongWritable,Text, NullWritable,Text> {
	@Override protected void reduce(
			LongWritable ignored, Iterable<Text> records,
			Reducer<LongWritable,Text, NullWritable,Text>.Context ctx)
		throws IOException, InterruptedException
	{
		for (Text rec : records)
			ctx.write(NullWritable.get(), rec);
	}
}

final class SortInputFormat
	extends BGZFSplitFileInputFormat<LongWritable,Text>
{
	@Override public RecordReader<LongWritable,Text>
		createRecordReader(InputSplit split, TaskAttemptContext ctx)
			throws InterruptedException, IOException
	{
		final RecordReader<LongWritable,Text> rr = new SortRecordReader();
		rr.initialize(split, ctx);
		return rr;
	}
}
final class SortRecordReader extends RecordReader<LongWritable,Text> {

	private final LongWritable key = new LongWritable();

	private final BlockCompressedLineRecordReader lineRR =
		new BlockCompressedLineRecordReader();

	@Override public void initialize(InputSplit spl, TaskAttemptContext ctx)
		throws IOException
	{
		lineRR.initialize(spl, ctx);
	}
	@Override public void close() throws IOException { lineRR.close(); }

	@Override public float getProgress() { return lineRR.getProgress(); }

	@Override public LongWritable getCurrentKey  () { return key; }
	@Override public Text         getCurrentValue() {
		return lineRR.getCurrentValue();
	}

	@Override public boolean nextKeyValue()
		throws IOException, CharacterCodingException
	{
		if (!lineRR.nextKeyValue())
			return false;

		Text line = getCurrentValue();
		int tabOne = line.find("\t");

		int rid = Integer.parseInt(line.decode(line.getBytes(), 0, tabOne));

		int tabTwo = line.find("\t", tabOne + 1);
		int posBeg = tabOne + 1;
		int posEnd = tabTwo - 1;

		int pos = Integer.parseInt(
			line.decode(line.getBytes(), posBeg, posEnd - posBeg + 1));

		key.set((long)rid << 32 | pos);
		return true;
	}
}
// LineRecordReader has only private fields so we have to copy the whole thing
// over. Make the key a NullWritable while we're at it, we don't need it
// anyway.
final class BlockCompressedLineRecordReader
	extends RecordReader<NullWritable,Text>
{
	private long start;
	private long pos;
	private long end;
	private BlockCompressedInputStream bin;
	private LineReader in;
	private int maxLineLength;
	private Text value = new Text();

	public void initialize(InputSplit genericSplit,
			TaskAttemptContext context) throws IOException {
		Configuration conf = context.getConfiguration();
		this.maxLineLength = conf.getInt("mapred.linerecordreader.maxlength",
			Integer.MAX_VALUE);

		FileSplit split = (FileSplit) genericSplit;
		start = (        split.getStart ()) << 16;
		end   = (start + split.getLength()) << 16;

		final Path file = split.getPath();
		FileSystem fs = file.getFileSystem(conf);

		bin =
			new BlockCompressedInputStream(
				new WrapSeekable<FSDataInputStream>(
					fs.open(file), fs.getFileStatus(file).getLen(), file));

		in = new LineReader(bin, conf);

		if (start != 0) {
			bin.seek(start);

			// Skip first line
			in.readLine(new Text());
			start = bin.getFilePointer();
		}
		this.pos = start;
	}

	public boolean nextKeyValue() throws IOException {
		while (pos <= end) {
			int newSize = in.readLine(value, maxLineLength);
			if (newSize == 0)
				return false;

			pos = bin.getFilePointer();
			if (newSize < maxLineLength)
				return true;
		}
		return false;
	}

	@Override public NullWritable getCurrentKey() { return NullWritable.get(); }
	@Override public Text getCurrentValue() { return value; }

	@Override public float getProgress() {
		if (start == end) {
			return 0.0f;
		} else {
			return Math.min(1.0f, (pos - start) / (float)(end - start));
		}
	}

	@Override public void close() throws IOException { in.close(); }
}

final class SortOutputFormat extends TextOutputFormat<NullWritable,Text> {
	public static final String OUTPUT_NAME_PROP =
		"hadoopbam.summarysort.output.name";

	@Override public RecordWriter<NullWritable,Text> getRecordWriter(
			TaskAttemptContext ctx)
		throws IOException
	{
		Path path = getDefaultWorkFile(ctx, "");
		FileSystem fs = path.getFileSystem(ctx.getConfiguration());

		return new TextOutputFormat.LineRecordWriter<NullWritable,Text>(
			new DataOutputStream(
				new BlockCompressedOutputStream(fs.create(path))));
	}

	@Override public Path getDefaultWorkFile(
			TaskAttemptContext context, String ext)
		throws IOException
	{
		String filename  = context.getConfiguration().get(OUTPUT_NAME_PROP);
		String extension = ext.isEmpty() ? ext : "." + ext;
		int    part      = context.getTaskAttemptID().getTaskID().getId();
		return new Path(super.getDefaultWorkFile(context, ext).getParent(),
			filename + "-" + String.format("%06d", part) + extension);
	}

	// Allow the output directory to exist, so that we can make multiple jobs
	// that write into it.
	@Override public void checkOutputSpecs(JobContext job)
		throws FileAlreadyExistsException, IOException
	{}
}
