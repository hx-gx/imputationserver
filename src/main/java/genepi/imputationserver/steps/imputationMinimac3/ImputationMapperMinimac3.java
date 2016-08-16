package genepi.imputationserver.steps.imputationMinimac3;

import genepi.hadoop.CacheStore;
import genepi.hadoop.HdfsUtil;
import genepi.hadoop.ParameterStore;
import genepi.hadoop.PreferenceStore;
import genepi.hadoop.log.Log;
import genepi.imputationserver.steps.vcf.VcfChunk;
import genepi.imputationserver.steps.vcf.VcfChunkOutput;
import genepi.imputationserver.util.FileMerger;
import genepi.imputationserver.util.FileMerger.BgzipSplitOutputStream;
import genepi.io.FileUtil;
import genepi.io.text.LineReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

public class ImputationMapperMinimac3 extends
		Mapper<LongWritable, Text, Text, Text> {

	private ImputationPipelineMinimac3 pipeline;

	public String folder;

	private String pattern;

	private String population;

	private String phasing;

	private String rounds;

	private String window;

	private String output;

	private String refFilename = "";

	private String mapShapeITPattern;

	private String mapHapiURPattern;

	private String mapShapeITFilename = "";

	private String mapHapiURFilename = "";

	private String mapEagleFilename = "";

	private String refEagleFilename = "";

	private String refEaglePattern = "";

	private int phasingWindow;

	private Log log;

	protected void setup(Context context) throws IOException,
			InterruptedException {

		log = new Log(context);

		// get parameters
		ParameterStore parameters = new ParameterStore(context);
		pattern = parameters.get(ImputationJobMinimac3.REF_PANEL_PATTERN);
		mapShapeITPattern = parameters
				.get(ImputationJobMinimac3.MAP_SHAPEIT_PATTERN);
		mapHapiURPattern = parameters
				.get(ImputationJobMinimac3.MAP_HAPIUR_PATTERN);
		output = parameters.get(ImputationJobMinimac3.OUTPUT);
		population = parameters.get(ImputationJobMinimac3.POPULATION);
		phasing = parameters.get(ImputationJobMinimac3.PHASING);
		rounds = parameters.get(ImputationJobMinimac3.ROUNDS);
		window = parameters.get(ImputationJobMinimac3.WINDOW);
		String hdfsPath = parameters.get(ImputationJobMinimac3.REF_PANEL_HDFS);
		String hdfsPathShapeITMap = parameters
				.get(ImputationJobMinimac3.MAP_SHAPEIT_HDFS);
		String hdfsPathHapiURMap = parameters
				.get(ImputationJobMinimac3.MAP_HAPIUR_HDFS);
		String hdfsPathMapEagle = parameters
				.get(ImputationJobMinimac3.MAP_EAGLE_HDFS);
		String referencePanel = FileUtil.getFilename(hdfsPath);

		String mapShapeIT = "";
		String mapHapiUR = "";
		String mapEagle = "";

		if (hdfsPathShapeITMap != null) {
			mapShapeIT = FileUtil.getFilename(hdfsPathShapeITMap);
		}
		if (hdfsPathHapiURMap != null) {
			mapHapiUR = FileUtil.getFilename(hdfsPathHapiURMap);
		}
		if (hdfsPathMapEagle != null) {
			mapEagle = FileUtil.getFilename(hdfsPathMapEagle);
		}

		String minimacBin = parameters.get(ImputationJobMinimac3.MINIMAC_BIN);

		// get cached files
		CacheStore cache = new CacheStore(context.getConfiguration());
		refFilename = cache.getArchive(referencePanel);

		mapShapeITFilename = cache.getArchive(mapShapeIT);
		mapHapiURFilename = cache.getArchive(mapHapiUR);
		mapEagleFilename = cache.getFile(mapEagle);

		refEaglePattern = parameters
				.get(ImputationJobMinimac3.REF_PANEL_EAGLE_PATTERN);
		String chr = parameters.get(ImputationJobMinimac3.CHROMOSOME);
		String chrFilename = refEaglePattern.replaceAll("\\$chr", chr);
		refEagleFilename = cache.getFile(FileUtil.getFilename(chrFilename));
		String indexFilename = cache.getFile(FileUtil.getFilename(chrFilename
				+ ".csi"));

		String minimacCommand = cache.getFile(minimacBin);
		String hapiUrCommand = cache.getFile("hapi-ur");
		String hapiUrPreprocessCommand = cache.getFile("insert-map.pl");
		String vcfCookerCommand = cache.getFile("vcfCooker");
		String vcf2HapCommand = cache.getFile("vcf2hap");
		String shapeItCommand = cache.getFile("shapeit");
		String eagleCommand = cache.getFile("eagle");
		String tabixCommand = cache.getFile("tabix");
		String bgzipCommand = cache.getFile("bgzip");

		// create temp directory
		PreferenceStore store = new PreferenceStore(context.getConfiguration());
		folder = store.getString("minimac.tmp");
		folder = FileUtil.path(folder, context.getTaskAttemptID().toString());
		FileUtil.createDirectory(folder);

		// create symbolic link --> index file is in the same folder as data

		if (!chr.startsWith("X")) {
			Files.createSymbolicLink(
					Paths.get(FileUtil.path(folder, "ref_" + chr + ".bcf")),
					Paths.get(refEagleFilename));
			Files.createSymbolicLink(
					Paths.get(FileUtil.path(folder, "ref_" + chr + ".bcf.csi")),
					Paths.get(indexFilename));
			// update reference path to symbolic link
			refEagleFilename = FileUtil.path(folder, "ref_" + chr + ".bcf");
		}

		phasingWindow = Integer.parseInt(store.getString("phasing.window"));

		// config pipeline
		pipeline = new ImputationPipelineMinimac3();
		pipeline.setMinimacCommand(minimacCommand);
		pipeline.setHapiUrCommand(hapiUrCommand);
		pipeline.setVcfCookerCommand(vcfCookerCommand);
		pipeline.setVcf2HapCommand(vcf2HapCommand);
		pipeline.setShapeItCommand(shapeItCommand);
		pipeline.setEagleCommand(eagleCommand);
		pipeline.setTabixCommand(tabixCommand);
		pipeline.setBgzipCommand(bgzipCommand);
		pipeline.setHapiUrPreprocessCommand(hapiUrPreprocessCommand);
		pipeline.setPhasingWindow(phasingWindow);

		// Minimac3
		pipeline.setRounds(Integer.parseInt(rounds));
		pipeline.setMinimacWindow(Integer.parseInt(window));

	}

	@Override
	protected void cleanup(Context context) throws IOException,
			InterruptedException {

		// delete temp directory
		log.close();
		FileUtil.deleteDirectory(folder);

	}

	public void map(LongWritable key, Text value, Context context)
			throws IOException, InterruptedException {

		if (value.toString() == null || value.toString().isEmpty()) {
			return;
		}

		VcfChunk chunk = new VcfChunk(value.toString());

		VcfChunkOutput outputChunk = new VcfChunkOutput(chunk, folder);

		HdfsUtil.get(chunk.getVcfFilename(), outputChunk.getVcfFilename());

		pipeline.setRefFilename(refFilename);
		pipeline.setPattern(pattern);
		pipeline.setMapShapeITPattern(mapShapeITPattern);
		pipeline.setMapShapeITFilename(mapShapeITFilename);
		pipeline.setMapHapiURFilename(mapHapiURFilename);
		pipeline.setMapHapiURPattern(mapHapiURPattern);
		pipeline.setMapEagleFilename(mapEagleFilename);
		pipeline.setRefEagleFilename(refEagleFilename);
		pipeline.setRefEaglePattern(refEaglePattern);
		pipeline.setPhasing(phasing);
		pipeline.setPopulation(population);

		boolean succesful = pipeline.execute(chunk, outputChunk);
		if (succesful) {
			log.info("Imputation for chunk " + chunk + " successful.");
		} else {
			log.stop("Imputation failed!", "");
			return;
		}

		// fix window bug in minimac
		int snpInfo = pipeline.fixInfoFile(chunk, outputChunk);
		log.info("  " + chunk.toString() + " Snps in info chunk: " + snpInfo);

		// store info file
		HdfsUtil.put(outputChunk.getInfoFixedFilename(),
				HdfsUtil.path(output, chunk + ".info"));

		long start = System.currentTimeMillis();

		// store vcf file (remove header)
		BgzipSplitOutputStream outData = new BgzipSplitOutputStream(
				HdfsUtil.create(HdfsUtil.path(output, chunk
						+ ".data.dose.vcf.gz")));

		BgzipSplitOutputStream outHeader = new BgzipSplitOutputStream(
				HdfsUtil.create(HdfsUtil.path(output, chunk
						+ ".header.dose.vcf.gz")));

		FileMerger.splitIntoHeaderAndData(outputChunk.getImputedVcfFilename(),
				outHeader, outData);
		long end = System.currentTimeMillis();

		System.out.println("Time filter and put: " + (end - start) + " ms");

		// store phased overlapping regions when phasing is eagle
		if ((phasing.equals("eagle") || phasing.equals("hapiur"))
				&& !outputChunk.isPhased()) {

			System.out.println("Phased output. Store additional files.");

			start = System.currentTimeMillis();
			BgzipSplitOutputStream outPhasedHead = new BgzipSplitOutputStream(
					HdfsUtil.create(HdfsUtil.path(output, chunk
							+ ".phased.head.vcf.gz")));

			FileMerger.writeVcfFile(outputChunk.getPhasedVcfFilename(),
					outPhasedHead, chunk.getStart() - phasingWindow,
					chunk.getStart() + phasingWindow);

			BgzipSplitOutputStream outPhasedTail = new BgzipSplitOutputStream(
					HdfsUtil.create(HdfsUtil.path(output, chunk
							+ ".phased.tail.vcf.gz")));
			FileMerger.writeVcfFile(outputChunk.getPhasedVcfFilename(),
					outPhasedTail, chunk.getEnd() - phasingWindow,
					chunk.getEnd() + phasingWindow);

			end = System.currentTimeMillis();

			System.out.println("Time head and tail: " + (end - start) + " ms");
		}
	}
}
