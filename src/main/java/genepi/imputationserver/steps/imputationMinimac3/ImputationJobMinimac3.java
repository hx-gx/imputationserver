package genepi.imputationserver.steps.imputationMinimac3;

import genepi.hadoop.CacheStore;
import genepi.hadoop.HadoopJob;
import genepi.hadoop.HdfsUtil;
import genepi.hadoop.log.LogCollector;
import genepi.imputationserver.steps.imputation.sort.ChunkKey;
import genepi.imputationserver.steps.imputation.sort.ChunkValue;
import genepi.imputationserver.steps.vcf.VcfFileUtil;
import genepi.io.FileUtil;

import java.io.IOException;
import java.util.List;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.NLineInputFormat;

public class ImputationJobMinimac3 extends HadoopJob {

	public static final String REF_PANEL = "MINIMAC_REFPANEL";

	public static final String REF_PANEL_PATTERN = "MINIMAC_REFPANEL_PATTERN";

	public static final String REF_PANEL_HDFS = "MINIMAC_REFPANEL_HDFS";

	public static final String OUTPUT = "MINIMAC_OUTPUT";

	public static final String PHASING = "MINIMAC_PHASING";
	
	public static final String ROUNDS = "MINIMAC_ROUNDS";

	public static final String MINIMAC_BIN = "MINIMAC_BIN";

	private String localOutput;

	private String refPanelHdfs;

	private String logFilename;

	private String folder;

	private String minimacBin;

	private boolean noCache = false;

	public ImputationJobMinimac3(String name) {

		super("imputation-minimac3");
		set("mapred.task.timeout", "720000000");
		set("mapred.map.tasks.speculative.execution", false);
		set("mapred.reduce.tasks.speculative.execution", false);
		getConfiguration().set("mapred.reduce.tasks", "22");
	}

	@Override
	public void setupJob(Job job) {

		NLineInputFormat.setNumLinesPerSplit(job, 1);

		job.setMapperClass(ImputationMapperMinimac3.class);
		job.setInputFormatClass(NLineInputFormat.class);

		job.setMapOutputKeyClass(ChunkKey.class);
		job.setMapOutputValueClass(ChunkValue.class);

		job.setOutputKeyClass(Text.class);
		job.setNumReduceTasks(0);

	}

	@Override
	protected void setupDistributedCache(CacheStore cache) {

		// installs and distributed alls binaries
		String data = "minimac-data";
		distribute(FileUtil.path(folder, "bin"), data, cache);

		// distributed refpanels

		String name = FileUtil.getFilename(refPanelHdfs);

		cache.addArchive(name, refPanelHdfs);
	}

	public void setFolder(String folder) {
		this.folder = folder;
	}

	protected void distribute(String folder, String hdfs, CacheStore cache) {
		String[] files = FileUtil.getFiles(folder, "");
		for (String file : files) {
			if (!HdfsUtil
					.exists(HdfsUtil.path(hdfs, FileUtil.getFilename(file)))
					|| noCache) {
				HdfsUtil.delete(HdfsUtil.path(hdfs, FileUtil.getFilename(file)));
				HdfsUtil.put(file,
						HdfsUtil.path(hdfs, FileUtil.getFilename(file)));
			}
			cache.addFile(HdfsUtil.path(hdfs, FileUtil.getFilename(file)));
		}
	}

	@Override
	public void after() {

		try {

			List<String> folders = HdfsUtil.getDirectories(getOutput());

			FileUtil.createDirectory(FileUtil.path(localOutput, "results"));

			for (String folder : folders) {

				String name = FileUtil.getFilename(folder);

				System.out.println("name is " + name);

				// merge all info files
				HdfsUtil.mergeAndGz(
						FileUtil.path(localOutput, "results", "chr" + name
								+ ".info.gz"), folder, true, ".info");

				// merge vcf output
				VcfFileUtil.mergeGz(
						FileUtil.path(localOutput, "results", "chr" + name
								+ ".dose.vcf.gz"), folder, ".dose.vcf");

			}

			// delete temporary files
			HdfsUtil.delete(getOutput());

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	@Override
	public void cleanupJob(Job job) {
		LogCollector collector = new LogCollector(job);
		try {
			collector.save(logFilename);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void setOutput(String output) {
		super.setOutput(output);
		set(OUTPUT, output);
	}

	public void setLocalOutput(String localOutput) {
		this.localOutput = localOutput;
	}

	public void setRefPanel(String refPanel) {
		set(REF_PANEL, refPanel);
	}

	public void setRefPanelPattern(String refPanelPattern) {
		set(REF_PANEL_PATTERN, refPanelPattern);
	}

	public void setRefPanelHdfs(String refPanelHdfs) {
		this.refPanelHdfs = refPanelHdfs;
		set(REF_PANEL_HDFS, refPanelHdfs);
	}

	public void setLogFilename(String logFilename) {
		this.logFilename = logFilename;
	}

	public void setPhasing(String phasing) {
		set(PHASING, phasing);
	}
	
	public void setRounds(String rounds) {
		set(ROUNDS, rounds);
	}

	public void setNoCache(boolean noCache) {
		this.noCache = noCache;
	}

	public void setMinimacBin(String minimacBin) {
		this.minimacBin = minimacBin;
		set(MINIMAC_BIN, minimacBin);
	}

}