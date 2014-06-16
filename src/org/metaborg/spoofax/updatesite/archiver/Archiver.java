package org.metaborg.spoofax.updatesite.archiver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

import org.json.JSONObject;

public class Archiver {

	public static long POLL_FREQ = 60000;

	public static String PROJECT_NAME; // = "spoofax";
	public static String JOBSET_NAME; // = "spoofax-master";
	public static String JOB_NAME; // = "build";
	public static String BUILD_ID = "latest";

	public static String REPOSITORY; // = "/tmp/updatesites";
	public static String REPOSITORY_PUBLISH_LINK; // =
													// "http://www.foobar.org/archiver/";

	public static String BUILD_LINK; // = "http://hydra.nixos.org/job/" +
										// PROJECT_NAME + "/" + JOBSET_NAME +
										// "/" + JOB_NAME + "/" + BUILD_ID;
	public static String DOWNLOAD_LINK_PREFIX = "http://hydra.nixos.org/build/";
	public static String DOWNLOAD_LINK_SUFFIX = "/download/2/";

	public static String REPOSITORY_INDEX; // = REPOSITORY + "/index.html";

	public static void main(String[] args) {
		PROJECT_NAME = args[0];
		JOBSET_NAME = args[1];
		JOB_NAME = args[2];
		REPOSITORY = args[3];
		REPOSITORY_PUBLISH_LINK = args[4];

		BUILD_LINK = "http://hydra.nixos.org/job/" + PROJECT_NAME + "/"
				+ JOBSET_NAME + "/" + JOB_NAME + "/" + BUILD_ID;

		REPOSITORY_INDEX = REPOSITORY + "/index.html";
		do {
			doUpdate();
			try {
				System.out.println("Sleeping " + POLL_FREQ + " ms");
				Thread.sleep(POLL_FREQ);
			} catch (InterruptedException e) {
			}
		} while (POLL_FREQ > 0);

	}

	private static void doUpdate() {
		URL url = null;
		HttpURLConnection httpConn = null;
		try {
			url = new URL(BUILD_LINK);
			httpConn = (HttpURLConnection) url.openConnection();
			httpConn.setDoInput(true);
			httpConn.setDoOutput(true);
			httpConn.setRequestMethod("GET");
			httpConn.setRequestProperty("Content-Type", "application/json");
			httpConn.connect();

			BufferedReader reader = new BufferedReader(new InputStreamReader(
					httpConn.getInputStream()));
			String response = null;
			String line = null;
			while ((line = reader.readLine()) != null) {
				response = line;
			}
			httpConn.disconnect();

			if (response == null) {
				System.err.println("Empty JSON result");
				return;
			}

			JSONObject buildInfo = new JSONObject(response);
			int buildId = buildInfo.getInt("id");
			int latestArchivedBuild = getLatestArchivedBuild();
			if (buildId > latestArchivedBuild) {
				System.out.println("Going to archive build " + buildId);
			} else {
				System.out.println("Build " + buildId + " is already archived");
				return;
			}

			File buildDir = new File(REPOSITORY, buildId + "");
			buildDir.mkdirs();

			String tarName = buildInfo.getJSONObject("buildproducts")
					.getJSONObject("2").getString("name");

			String downloadUrl = DOWNLOAD_LINK_PREFIX + buildId
					+ DOWNLOAD_LINK_SUFFIX;
			File tarDestination = new File(buildDir, tarName);

			System.out.println("Downloading payload");
			downloadFile(downloadUrl, tarDestination);
			System.out.println("Payload downloaded");

			System.out.println("Extracting payload");

			Process extractor = new ProcessBuilder("tar", "-zxf",
					tarDestination.getAbsolutePath(), "-C",
					buildDir.getAbsolutePath()).start();

			int extractionResult = extractor.waitFor();
			BufferedReader extractorErrRd = new BufferedReader(
					new InputStreamReader(extractor.getErrorStream()));

			String extractorL;
			while ((extractorL = extractorErrRd.readLine()) != null) {
				System.err.println(extractorL);
			}

			if (extractionResult != 0) {
				System.err.println("Extraction failed");
				return;
			} else {
				System.out.println("Extraction successful");
			}
			tarDestination.delete();

			new File(buildDir, "site").renameTo(new File(buildDir, buildInfo
					.getString("nixname")));

			buildDir.setLastModified(buildInfo.getLong("timestamp") * 1000);

			generateIndexHtml(buildInfo.getString("jobset"));
		} catch (IOException | InterruptedException e) {
			System.err.println("Error ecountered during update");
			e.printStackTrace();
		} finally {
			if (httpConn != null) {
				httpConn.disconnect();
			}
		}
	}

	private static void generateIndexHtml(String jobName)
			throws FileNotFoundException {
		final String htmlPrefix = "<html><head><title>"
				+ jobName
				+ " | Build archive</title></head><body><table><tr><th>Build ID</th><th>Timestamp</th><th>Version</th><th>Link</th></tr>";
		final String htmlSuffix = "</table></body></html>";
		StringBuilder sb = new StringBuilder(htmlPrefix);
		File[] files = new File(REPOSITORY).listFiles();
		Arrays.sort(files, new Comparator<File>() {

			@Override
			public int compare(File o1, File o2) {
				return (int) (o2.lastModified() - o1.lastModified());
			}
		});

		for (File f : files) {
			if (f.isDirectory()) {
				File versionDir = null;
				File[] fcs = f.listFiles();
				for (File fc : fcs) {
					if (fc.isDirectory()) {
						versionDir = fc;
						break;
					}
				}
				sb.append("<tr>");
				sb.append("<td>" + f.getName() + "</td>");
				sb.append("<td>"
						+ new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S")
								.format(new Date(f.lastModified())) + "</td>");
				sb.append("<td>" + versionDir.getName() + "</td>");
				sb.append("<td><a href=\"" + REPOSITORY_PUBLISH_LINK
						+ f.getName() + "/" + versionDir.getName() + "\">"
						+ versionDir.getName() + "</a></td>");
				sb.append("</tr>");
			}
		}
		sb.append(htmlSuffix);

		File indexFile = new File(REPOSITORY_INDEX);
		if (indexFile.exists()) {
			indexFile.delete();
		}

		PrintWriter out = new PrintWriter(indexFile);
		out.print(sb.toString());
		out.close();
	}

	private static int getLatestArchivedBuild() {
		File repository = new File(REPOSITORY);
		if (!(repository.exists() && repository.isDirectory() && repository
				.canRead())) {
			System.err
					.println("Repository does not exist, is not a directory or is not readable");
			return -1;
		}

		File[] files = repository.listFiles();
		File latestArchived = null;
		for (File file : files) {
			if (file.isDirectory()
					&& (latestArchived == null || Integer
							.parseInt(latestArchived.getName()) < Integer
							.parseInt(file.getName()))) {
				latestArchived = file;
			}
		}

		return latestArchived != null ? Integer.parseInt(latestArchived
				.getName()) : -1;
	}

	private static File downloadFile(String stringUrl, File destination)
			throws IOException {
		URL url = null;
		URLConnection con = null;
		try {
			int i;
			url = new URL(stringUrl);
			con = url.openConnection();
			BufferedInputStream bis = new BufferedInputStream(
					con.getInputStream());
			BufferedOutputStream bos = new BufferedOutputStream(
					new FileOutputStream(destination));
			while ((i = bis.read()) != -1) {
				bos.write(i);
			}
			bos.flush();
			bis.close();
			bos.close();
			return destination;
		} catch (IOException e) {
			System.err.println("Failed to download file from " + stringUrl);
			throw e;
		}
	}

}
