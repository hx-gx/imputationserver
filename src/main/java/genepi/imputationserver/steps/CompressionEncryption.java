package genepi.imputationserver.steps;

import genepi.hadoop.HdfsUtil;
import genepi.hadoop.PreferenceStore;
import genepi.hadoop.command.Command;
import genepi.hadoop.common.WorkflowContext;
import genepi.hadoop.common.WorkflowStep;
import genepi.imputationserver.steps.vcf.MergedVcfFile;
import genepi.imputationserver.util.FileMerger;
import genepi.imputationserver.util.LigateTool;
import genepi.io.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

public class CompressionEncryption extends WorkflowStep {

	@Override
	public boolean run(WorkflowContext context) {

		String workingDirectory = getFolder(InputValidation.class);

		String output = context.get("outputimputation");
		String localOutput = context.get("local");

		// read config if mails should be sent
		String folderConfig = getFolder(CompressionEncryption.class);
		PreferenceStore store = new PreferenceStore(new File(FileUtil.path(
				folderConfig, "job.config")));

		String notification = "no";
		if (store.getString("minimac.sendmail") != null
				&& !store.getString("minimac.sendmail").equals("")) {
			notification = store.getString("minimac.sendmail");
		}

		String password;

		if (notification.equals("yes")) {
			// create one-time password
			password = RandomStringUtils.randomAlphanumeric(13);
		} else {
			password = "imputation@michigan";
		}
		try {

			context.beginTask("Export data...");

			List<String> folders = HdfsUtil.getDirectories(output);

			// export all chromosomes

			for (String folder : folders) {

				String name = FileUtil.getFilename(folder);
				context.println("Export and merge chromosome " + name);

				// create temp fir
				String temp = FileUtil.path(localOutput, "temp");
				FileUtil.createDirectory(temp);

				// output files
				String doseOutput = FileUtil.path(temp, "chr" + name
						+ ".info.gz");
				String vcfOutput = FileUtil.path(temp, "chr" + name
						+ ".dose.vcf.gz");

				// merge all info files
				FileMerger.mergeAndGz(doseOutput, folder, true, ".info");

				List<String> dataFiles = findFiles(folder, ".data.dose.vcf.gz");
				List<String> headerFiles = findFiles(folder,
						".header.dose.vcf.gz");

				List<String> headFiles = findFiles(folder, ".head.vcf.gz");
				List<String> tailFiles = findFiles(folder, ".tail.vcf.gz");

				List<Boolean> swapFiles = new Vector<Boolean>();

				// check if head- and tail-files are there and perform ligate
				if (headFiles.size() == tailFiles.size()
						&& headFiles.size() > 0) {
					context.println("Execute ligate algorithm...");
					swapFiles = LigateTool.ligate(headFiles, tailFiles);
				} else {
					for (int i = 0; i < dataFiles.size(); i++) {
						swapFiles.add(false);
					}
				}
				MergedVcfFile vcfFile = new MergedVcfFile(vcfOutput);

				// add one header
				String header = headerFiles.get(0);
				vcfFile.addFile(HdfsUtil.open(header), false);

				// add data files
				for (int i = 0; i < dataFiles.size(); i++) {
					String file = dataFiles.get(i);
					Boolean swapFile = swapFiles.get(i);
					context.println("Read file " + file
							+ (swapFile ? " and swap" : "") + "...");
					vcfFile.addFile(HdfsUtil.open(file), swapFile);
				}

				vcfFile.close();

				Command tabix = new Command(FileUtil.path(workingDirectory,
						"bin", "tabix"));
				tabix.setSilent(false);
				tabix.setParams("-f", vcfOutput);
				if (tabix.execute() != 0) {
					context.endTask(
							"Error during index creation: " + tabix.getStdOut(),
							WorkflowContext.ERROR);
					return false;
				}

				ZipParameters param = new ZipParameters();
				param.setEncryptFiles(true);
				param.setPassword(password);
				param.setEncryptionMethod(Zip4jConstants.ENC_METHOD_STANDARD);

				// create zip file
				ArrayList<File> files = new ArrayList<File>();
				files.add(new File(vcfOutput));
				files.add(new File(vcfOutput + ".tbi"));
				files.add(new File(doseOutput));

				ZipFile file = new ZipFile(new File(FileUtil.path(localOutput,
						"chr_" + name + ".zip")));
				file.createZipFile(files, param);

				// delete temp dir
				FileUtil.deleteDirectory(temp);

			}

			// delete temporary files
			// HdfsUtil.delete(output);

			context.endTask("Exported data.", WorkflowContext.OK);

		} catch (Exception e) {
			e.printStackTrace();
			context.endTask("Data compression failed: " + e.getMessage(),
					WorkflowContext.ERROR);
			return false;
		}

		// submit counters!
		context.submitCounter("samples");
		context.submitCounter("genotypes");
		context.submitCounter("chromosomes");
		context.submitCounter("runs");
		// submit panel and phasing method counters
		String reference = context.get("refpanel");
		String phasing = context.get("phasing");
		context.submitCounter("refpanel_" + reference);
		context.submitCounter("phasing_" + phasing);
		context.submitCounter("23andme-input");

		// send email
		if (notification.equals("yes")) {

			Object mail = context.getData("cloudgene.user.mail");
			Object name = context.getData("cloudgene.user.name");

			if (mail != null) {

				String subject = "Job " + context.getJobName()
						+ " is complete.";
				String message = "Dear "
						+ name
						+ ",\nthe password for the imputation results is: "
						+ password
						+ "\n\nThe results can be downloaded from https://imputationserver.sph.umich.edu/start.html#!jobs/"
						+ context.getJobName() + "/results";

				try {
					context.sendMail(subject, message);
					context.ok("We have sent an email to <b>" + mail
							+ "</b> with the password.");
					return true;
				} catch (Exception e) {
					context.error("Data compression failed: " + e.getMessage());
					return false;
				}

			} else {
				context.error("No email address found. Please enter your email address (Account -> Profile).");
				return false;
			}

		} else {
			context.ok("Email notification (and therefore encryption) is disabled. All results are encrypted with password <b>"
					+ password + "</b>");
			return true;
		}

	}

	private List<String> findFiles(String folder, String pattern)
			throws IOException {

		Configuration conf = new Configuration();

		FileSystem fileSystem = FileSystem.get(conf);
		Path pathFolder = new Path(folder);
		FileStatus[] files = fileSystem.listStatus(pathFolder);

		List<String> dataFiles = new Vector<String>();
		for (FileStatus file : files) {
			if (!file.isDir() && !file.getPath().getName().startsWith("_")
					&& file.getPath().getName().endsWith(pattern)) {
				dataFiles.add(file.getPath().toString());
			}
		}
		Collections.sort(dataFiles);
		return dataFiles;
	}

}
