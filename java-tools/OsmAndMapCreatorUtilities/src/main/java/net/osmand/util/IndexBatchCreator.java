package net.osmand.util;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.SimpleFormatter;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.impl.Jdk14Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParserException;

import net.osmand.IndexConstants;
import net.osmand.MapCreatorVersion;
import net.osmand.PlatformUtil;
import net.osmand.binary.MapZooms;
import net.osmand.impl.ConsoleProgressImplementation;
import net.osmand.obf.preparation.DBDialect;
import net.osmand.obf.preparation.IndexCreator;
import net.osmand.obf.preparation.IndexCreatorSettings;
import net.osmand.osm.MapRenderingTypesEncoder;
import net.osmand.util.CountryOcbfGeneration.CountryRegion;
import rtree.RTree;
import software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder;
import software.amazon.awssdk.services.batch.BatchClient;
import software.amazon.awssdk.services.batch.BatchClientBuilder;
import software.amazon.awssdk.services.batch.model.SubmitJobRequest;
import software.amazon.awssdk.services.batch.model.SubmitJobResponse;


public class IndexBatchCreator {

	private static final int INMEM_LIMIT = 2000;

	protected static final Log log = PlatformUtil.getLog(IndexBatchCreator.class);

	public static final String GEN_LOG_EXT = ".gen.log";


	public static class RegionCountries {
		String namePrefix = ""; // for states of the country
		String nameSuffix = "";
		Map<String, RegionSpecificData> regionNames = new LinkedHashMap<String, RegionSpecificData>();
		String siteToDownload = "";
	}
	
	private static class AwsJobDefinition {
		Map<String, String> params = new LinkedHashMap<>();
		String name;
		String queue;
		String definition;
		int sizeUpToMB = -1;
	}

	private static class RegionSpecificData {
		public String downloadName;
		public boolean indexSRTM = true;
		public boolean indexPOI = true;
		public boolean indexTransport = true;
		public boolean indexAddress = true;
		public boolean indexMap = true;
		public boolean indexRouting = true;
	}


	// process atributtes
	File skipExistingIndexes;
	MapZooms mapZooms = null;
	Integer zoomWaySmoothness = null;

	File osmDirFiles;
	File indexDirFiles;
	File workDir;
	String srtmDir;
	List<AwsJobDefinition> awsJobs = new ArrayList<IndexBatchCreator.AwsJobDefinition>();

	boolean indexPOI = false;
	boolean indexTransport = false;
	boolean indexAddress = false;
	boolean indexMap = false;
	boolean indexRouting = false;

	private String wget;

	private DBDialect osmDbDialect;
	private String renderingTypesFile;

	public static void main(String[] args) {
		IndexBatchCreator creator = new IndexBatchCreator();
		if (args == null || args.length == 0) {
			System.out
					.println("Please specify -local parameter or path to batch.xml configuration file as 1 argument.");
			throw new IllegalArgumentException(
					"Please specify -local parameter or path to batch.xml configuration file as 1 argument.");
		}
		String name = args[0];
		InputStream stream;
		try {
			stream = new FileInputStream(name);
		} catch (FileNotFoundException e) {
			throw new IllegalArgumentException("XML configuration file not found : " + name, e);
		}

		try {
			Document params = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);
			List<RegionCountries> countriesToDownload = creator.setupProcess(params);
			creator.runBatch(countriesToDownload);
		} catch (Exception e) {
			System.out.println("XML configuration file could not be read from " + name);
			e.printStackTrace();
			log.error("XML configuration file could not be read from " + name, e);
		} finally {
			safeClose(stream, "Error closing stream for " + name);
		}
	}

	public List<RegionCountries> setupProcess(Document doc)
			throws SAXException, IOException, ParserConfigurationException, XmlPullParserException {
		NodeList list = doc.getElementsByTagName("process");
		if (list.getLength() != 1) {
			throw new IllegalArgumentException("You should specify exactly 1 process element!");
		}
		Element process = (Element) list.item(0);
		IndexCreator.REMOVE_POI_DB = true;
		String file = process.getAttribute("skipExistingIndexesAt");
		if (file != null && new File(file).exists()) {
			skipExistingIndexes = new File(file);
		}
		wget = process.getAttribute("wget");

		indexPOI = Boolean.parseBoolean(process.getAttribute("indexPOI"));
		indexMap = Boolean.parseBoolean(process.getAttribute("indexMap"));
		indexRouting = process.getAttribute("indexRouting") == null
				|| process.getAttribute("indexRouting").equalsIgnoreCase("true");
		indexTransport = Boolean.parseBoolean(process.getAttribute("indexTransport"));
		indexAddress = Boolean.parseBoolean(process.getAttribute("indexAddress"));
		parseProcessAttributes(process);

		list = doc.getElementsByTagName("process_attributes");
		if (list.getLength() == 1) {
			parseProcessAttributes((Element) list.item(0));
		}

		String dir = process.getAttribute("directory_for_osm_files");
		if (dir == null || !new File(dir).exists()) {
			throw new IllegalArgumentException(
					"Please specify directory with .osm or .osm.bz2 files as directory_for_osm_files (attribute)" //$NON-NLS-1$
							+ dir);
		}
		osmDirFiles = new File(dir);

		srtmDir = process.getAttribute("directory_for_srtm_files");

		dir = process.getAttribute("directory_for_index_files");
		if (dir == null || !new File(dir).exists()) {
			throw new IllegalArgumentException(
					"Please specify directory with generated index files  as directory_for_index_files (attribute)"); //$NON-NLS-1$
		}
		indexDirFiles = new File(dir);
		workDir = indexDirFiles;
		dir = process.getAttribute("directory_for_generation");
		if (dir != null && new File(dir).exists()) {
			workDir = new File(dir);
		}

		NodeList awsl = process.getElementsByTagName("aws");
		for (int j = 0; j < awsl.getLength(); j++) {
			Element aws = (Element) awsl.item(j);
			NodeList jobs = aws.getElementsByTagName("job");
			for (int k = 0; k < jobs.getLength(); k++) {
				Element jbe = (Element) jobs.item(k);
				AwsJobDefinition jd = new AwsJobDefinition();
				jd.definition = jbe.getAttribute("definition");
				jd.name = jbe.getAttribute("name");
				jd.queue = jbe.getAttribute("queue");
				if (!Algorithms.isEmpty(jbe.getAttribute("sizeUpToMB"))) {
					jd.sizeUpToMB = Integer.parseInt(jbe.getAttribute("sizeUpToMB"));
				}
				NodeList params = jbe.getElementsByTagName("parameter");
				for (int l = 0; l < params.getLength(); l++) {
					Element jbep = (Element) params.item(l);
					jd.params.put(jbep.getAttribute("k"), jbep.getAttribute("v"));
				}
				jd.name = jbe.getAttribute("name");
				awsJobs.add(jd);
			}
		}
		List<RegionCountries> countriesToDownload = new ArrayList<RegionCountries>();
		parseCountriesToDownload(doc, countriesToDownload);
		return countriesToDownload;
	}

	private void parseCountriesToDownload(Document doc, List<RegionCountries> countriesToDownload) throws IOException, XmlPullParserException {
		NodeList regions = doc.getElementsByTagName("regions");
		for (int i = 0; i < regions.getLength(); i++) {
			Element el = (Element) regions.item(i);
			if (!Boolean.parseBoolean(el.getAttribute("skip"))) {
				RegionCountries countries = new RegionCountries();
				countries.siteToDownload = el.getAttribute("siteToDownload");
				if (countries.siteToDownload == null) {
					continue;
				}
				countries.namePrefix = el.getAttribute("region_prefix");
				if (countries.namePrefix == null) {
					countries.namePrefix = "";
				}
				countries.nameSuffix = el.getAttribute("region_suffix");
				if (countries.nameSuffix == null) {
					countries.nameSuffix = "";
				}
				
				NodeList nRegionsList = el.getElementsByTagName("regionList");
				for (int j = 0; j < nRegionsList.getLength(); j++) {
					Element nregionList = (Element) nRegionsList.item(j);
					String url = nregionList.getAttribute("url");
					if (url != null) {
						String filterStartWith = nregionList.getAttribute("filterStartsWith");
						String filterContains = nregionList.getAttribute("filterContains");
						CountryOcbfGeneration ocbfGeneration = new CountryOcbfGeneration();
						log.warn("Download region list from " + url);
						CountryRegion regionStructure = ocbfGeneration.parseRegionStructure(new URL(url).openStream());
						Iterator<CountryRegion> it = regionStructure.iterator();
						int total = 0;
						int before = countries.regionNames.size();
						while (it.hasNext()) {
							CountryRegion cr = it.next();
							if (cr.map && !cr.jointMap) {
								total ++;
								RegionSpecificData dt = new RegionSpecificData();
								dt.downloadName = cr.getDownloadName();
								if (!Algorithms.isEmpty(filterStartWith)
										&& !dt.downloadName.toLowerCase().startsWith(filterStartWith.toLowerCase())) {
									continue;
								}
								if (!Algorithms.isEmpty(filterContains)
										&& !dt.downloadName.toLowerCase().contains(filterContains.toLowerCase())) {
									continue;
								}
								countries.regionNames.put(dt.downloadName, dt);
							}
						}
						log.warn(String.format("Accepted %d from %d", countries.regionNames.size() - before, total));
					}
				}
				NodeList ncountries = el.getElementsByTagName("region");
				log.info("Region to download " + countries.siteToDownload);
				for (int j = 0; j < ncountries.getLength(); j++) {
					Element ncountry = (Element) ncountries.item(j);
					String name = ncountry.getAttribute("name");
					RegionSpecificData data = new RegionSpecificData();
					data.indexSRTM = ncountry.getAttribute("indexSRTM") == null
							|| ncountry.getAttribute("indexSRTM").equalsIgnoreCase("true");
					String index = ncountry.getAttribute("index");
					if (index != null && index.length() > 0) {
						data.indexAddress = index.contains("address");
						data.indexMap = index.contains("map");
						data.indexTransport = index.contains("transport");
						data.indexRouting = index.contains("routing");
						data.indexPOI = index.contains("poi");
					}
					String dname = ncountry.getAttribute("downloadName");
					data.downloadName = dname == null || dname.length() == 0 ? name : dname;
					if (name != null && !Boolean.parseBoolean(ncountry.getAttribute("skip"))) {
						countries.regionNames.put(name, data);
					}
				}
				countriesToDownload.add(countries);

			}
		}
	}

	private void parseProcessAttributes(Element process) {
		String zooms = process.getAttribute("mapZooms");
		if(zooms == null || zooms.length() == 0){
			mapZooms = MapZooms.getDefault();
		} else {
			mapZooms = MapZooms.parseZooms(zooms);
		}

		String szoomWaySmoothness = process.getAttribute("zoomWaySmoothness");
		if(szoomWaySmoothness != null && !szoomWaySmoothness.isEmpty()){
			zoomWaySmoothness = Integer.parseInt(szoomWaySmoothness);
		}
		renderingTypesFile = process.getAttribute("renderingTypesFile");

		String osmDbDialect = process.getAttribute("osmDbDialect");
		if(osmDbDialect != null && osmDbDialect.length() > 0){
			try {
				this.osmDbDialect = DBDialect.valueOf(osmDbDialect.toUpperCase());
			} catch (RuntimeException e) {
			}
		}
	}

	public void runBatch(List<RegionCountries> countriesToDownload ){
		Set<String> alreadyGeneratedFiles = new LinkedHashSet<String>();
		if (!countriesToDownload.isEmpty()) {
			downloadFilesAndGenerateIndex(countriesToDownload, alreadyGeneratedFiles);
		}
		generatedIndexes(alreadyGeneratedFiles);
	}



	protected void downloadFilesAndGenerateIndex(List<RegionCountries> countriesToDownload,
			Set<String> alreadyGeneratedFiles) {
		// clean before downloading
		// for(File f : osmDirFiles.listFiles()){
		// log.info("Delete old file " + f.getName()); //$NON-NLS-1$
		// f.delete();
		// }

		for (RegionCountries regionCountries : countriesToDownload) {
			String prefix = regionCountries.namePrefix;
			String site = regionCountries.siteToDownload;
			String suffix = regionCountries.nameSuffix;
			for (String name : regionCountries.regionNames.keySet()) {
				RegionSpecificData regionSpecificData = regionCountries.regionNames.get(name);
				name = name.toLowerCase();
				String url = MessageFormat.format(site, regionSpecificData.downloadName);

				String regionName = prefix + name;
				String fileName = Algorithms.capitalizeFirstLetterAndLowercase(prefix + name + suffix);
				log.warn("----------- Check existing " + fileName);

				if (skipExistingIndexes != null) {
					File bmif = new File(skipExistingIndexes,
							fileName + "_" + IndexConstants.BINARY_MAP_VERSION + IndexConstants.BINARY_MAP_INDEX_EXT);
					File bmifz = new File(skipExistingIndexes, bmif.getName() + ".zip");
					if (bmif.exists() || bmifz.exists()) {
						continue;
					}
				}
				log.warn("----------- Get " + fileName + " " + url + " ----------");
				File toSave = downloadFile(url, fileName);
				if (toSave != null) {
					generateIndex(toSave, regionName, regionSpecificData, alreadyGeneratedFiles);
				}
			}
		}
	}

	protected File downloadFile(String url, String regionName) {
		if (!url.startsWith("http")) {
			return new File(url);
		}
		String ext = ".osm";
		if (url.endsWith(".osm.bz2")) {
			ext = ".osm.bz2";
		} else if (url.endsWith(".pbf")) {
			ext = ".osm.pbf";
		}
		File toIndex = null;
		File saveTo = new File(osmDirFiles, regionName + ext);
		if (wget == null || wget.trim().length() == 0) {
			toIndex = internalDownload(url, saveTo);
		} else {
			toIndex = wgetDownload(url, saveTo);
		}
		if (toIndex == null) {
			saveTo.delete();
		}
		return toIndex;
	}

	private File wgetDownload(String url,  File toSave)
	{
		BufferedReader wgetOutput = null;
		OutputStream wgetInput = null;
		Process wgetProc = null;
		try {
			log.info("Executing " + wget + " " + url + " -O "+ toSave.getCanonicalPath()); //$NON-NLS-1$//$NON-NLS-2$ $NON-NLS-3$
			ProcessBuilder exec = new ProcessBuilder(wget, "--read-timeout=5", "--progress=dot:binary", url, "-O", //$NON-NLS-1$//$NON-NLS-2$ $NON-NLS-3$
					toSave.getCanonicalPath());
			exec.redirectErrorStream(true);
			wgetProc = exec.start();
			wgetOutput = new BufferedReader(new InputStreamReader(wgetProc.getInputStream()));
			String line;
			while ((line = wgetOutput.readLine()) != null) {
				log.info("wget output:" + line); //$NON-NLS-1$
			}
			int exitValue = wgetProc.waitFor();
			wgetProc = null;
			if (exitValue != 0) {
				log.error("Wget exited with error code: " + exitValue); //$NON-NLS-1$
			} else {
				return toSave;
			}
		} catch (IOException e) {
			log.error("Input/output exception " + toSave.getName() + " downloading from " + url + "using wget: " + wget, e); //$NON-NLS-1$ //$NON-NLS-2$ $NON-NLS-3$
		} catch (InterruptedException e) {
			log.error("Interrupted exception " + toSave.getName() + " downloading from " + url + "using wget: " + wget, e); //$NON-NLS-1$ //$NON-NLS-2$ $NON-NLS-3$
		} finally {
			safeClose(wgetOutput, ""); //$NON-NLS-1$
			safeClose(wgetInput, ""); //$NON-NLS-1$
			if (wgetProc != null) {
				wgetProc.destroy();
			}
		}
		return null;
	}

	private final static int DOWNLOAD_DEBUG = 1 << 20;
	private final static int BUFFER_SIZE = 1 << 15;
	private File internalDownload(String url, File toSave) {
		int count = 0;
		int downloaded = 0;
		int mbDownloaded = 0;
		byte[] buffer = new byte[BUFFER_SIZE];
		OutputStream ostream = null;
		InputStream stream = null;
		try {
			ostream = new FileOutputStream(toSave);
			stream = new URL(url).openStream();
			log.info("Downloading country " + toSave.getName() + " from " + url);  //$NON-NLS-1$//$NON-NLS-2$
			while ((count = stream.read(buffer)) != -1) {
				ostream.write(buffer, 0, count);
				downloaded += count;
				if(downloaded > DOWNLOAD_DEBUG){
					downloaded -= DOWNLOAD_DEBUG;
					mbDownloaded += (DOWNLOAD_DEBUG>>20);
					log.info(mbDownloaded +" megabytes downloaded of " + toSave.getName());
				}
			}
			return toSave;
		} catch (IOException e) {
			log.error("Input/output exception " + toSave.getName() + " downloading from " + url, e); //$NON-NLS-1$ //$NON-NLS-2$
		} finally {
			safeClose(ostream, "Input/output exception " + toSave.getName() + " to close stream "); //$NON-NLS-1$ //$NON-NLS-2$
			safeClose(stream, "Input/output exception " + url + " to close stream "); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return null;
	}

	private static void safeClose(Closeable ostream, String message) {
		if (ostream != null) {
			try {
				ostream.close();
			} catch (Exception e) {
				log.error(message, e); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
	}

	protected void generatedIndexes(Set<String> alreadyGeneratedFiles) {
		for (File f : getSortedFiles(osmDirFiles)) {
			if (alreadyGeneratedFiles.contains(f.getName())) {
				continue;
			}
			if (f.getName().endsWith(".osm.bz2") || f.getName().endsWith(".osm") || f.getName().endsWith(".osm.pbf")) {
				if (skipExistingIndexes != null) {
					int i = f.getName().indexOf(".osm");
					String name = Algorithms.capitalizeFirstLetterAndLowercase(f.getName().substring(0, i));
					File bmif = new File(skipExistingIndexes, name + "_" + IndexConstants.BINARY_MAP_VERSION
							+ IndexConstants.BINARY_MAP_INDEX_EXT_ZIP);
					log.info("Check if " + bmif.getAbsolutePath() + " exists");
					if (bmif.exists()) {
						continue;
					}
				}
				generateIndex(f, null, null, alreadyGeneratedFiles);
			}
		}
		log.info("GENERATING INDEXES FINISHED ");
	}



	protected void generateIndex(File file, String regionName, RegionSpecificData rdata, Set<String> alreadyGeneratedFiles) {
		String fileMapName = file.getName();
		int i = file.getName().indexOf('.');
		if (i > -1) {
			fileMapName = Algorithms.capitalizeFirstLetterAndLowercase(file.getName().substring(0, i));
		}
		log.warn("-------------------------------------------");
		log.warn("----------- Generate " + file.getName() + "\n\n\n");
		if (Algorithms.isEmpty(regionName)) {
			regionName = fileMapName;
		} else {
			regionName = Algorithms.capitalizeFirstLetterAndLowercase(regionName);
		}
		String targetMapFileName = fileMapName + "_" + IndexConstants.BINARY_MAP_VERSION + IndexConstants.BINARY_MAP_INDEX_EXT;
		for (AwsJobDefinition jd : awsJobs) {
			if (jd.sizeUpToMB < 0 || file.length() < jd.sizeUpToMB * 1024 * 1024) {
				generateAwsIndex(jd, file, targetMapFileName, rdata, alreadyGeneratedFiles);
				return;
			}
		}
		generateLocalIndex(file, regionName, targetMapFileName, rdata, alreadyGeneratedFiles);
	}
	
	
	private void generateAwsIndex(AwsJobDefinition jd, File file, String targetFileName,
			RegionSpecificData rdata, Set<String> alreadyGeneratedFiles) {
		String fileParam = file.getName().substring(0, file.getName().indexOf('.'));
		String currentMonth = new SimpleDateFormat("yyyy-MM").format(new Date());
		String name = MessageFormat.format(jd.name, fileParam, currentMonth);
		String queue = MessageFormat.format(jd.queue, fileParam, currentMonth);
		String definition = MessageFormat.format(jd.definition, fileParam, currentMonth);
		alreadyGeneratedFiles.add(file.getName());
		BatchClient client = BatchClient.builder().build();
		SubmitJobRequest.Builder pr = SubmitJobRequest.builder().jobName(name).jobQueue(queue)
				.jobDefinition(definition);
		LinkedHashMap<String, String> pms = new LinkedHashMap<>();
		Iterator<Entry<String, String>> it = jd.params.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, String> e = it.next();
			pms.put(e.getKey(), MessageFormat.format(e.getValue(), fileParam, currentMonth));
		}
		pr.parameters(pms);
		SubmitJobRequest req = pr.build();
		System.out.println("Submit aws request: " + req);
		SubmitJobResponse response = client.submitJob(req);
		// TODO wait response
		System.out.println(response);
	}

	protected void generateLocalIndex(File file, String regionName, String mapFileName, RegionSpecificData rdata, Set<String> alreadyGeneratedFiles) {
		try {
			// be independent of previous results
			RTree.clearCache();

			DBDialect osmDb = this.osmDbDialect;
			if (file.length() / 1024 / 1024 > INMEM_LIMIT && osmDb == DBDialect.SQLITE_IN_MEMORY) {
				log.warn("Switching SQLITE in memory dialect to SQLITE");
				osmDb = DBDialect.SQLITE;
			}
			final boolean indAddr = indexAddress && (rdata == null || rdata.indexAddress);
			final boolean indPoi = indexPOI && (rdata == null || rdata.indexPOI);
			final boolean indTransport = indexTransport && (rdata == null || rdata.indexTransport);
			final boolean indMap = indexMap && (rdata == null || rdata.indexMap);
			final boolean indRouting = indexRouting && (rdata == null || rdata.indexRouting);
			if(!indAddr && !indPoi && !indTransport && !indMap && !indRouting) {
				log.warn("! Skip country because nothing to index !");
				file.delete();
				return;
			}
			IndexCreatorSettings settings = new IndexCreatorSettings();
			settings.indexMap = indMap;
			settings.indexAddress = indAddr;
			settings.indexPOI = indPoi;
			settings.indexTransport = indTransport;
			settings.indexRouting = indRouting;
			if(zoomWaySmoothness != null){
				settings.zoomWaySmoothness = zoomWaySmoothness;
			}
			boolean worldMaps = regionName.toLowerCase().contains("world") ;
			if (worldMaps) {
				if (regionName.toLowerCase().contains("basemap")) {
					return;
				}
				if (regionName.toLowerCase().contains("seamarks")) {
					settings.keepOnlySeaObjects = true;
					settings.indexTransport = false;
					settings.indexAddress = false;
				}
			} else {
				if (!Algorithms.isEmpty(srtmDir) && (rdata == null || rdata.indexSRTM)) {
					settings.srtmDataFolderUrl = srtmDir;
				}
			}
			IndexCreator indexCreator = new IndexCreator(workDir, settings);
			
			indexCreator.setDialects(osmDb, osmDb);
			indexCreator.setLastModifiedDate(file.lastModified());
			indexCreator.setRegionName(regionName);
			indexCreator.setMapFileName(mapFileName);
			try {
				alreadyGeneratedFiles.add(file.getName());
				Log warningsAboutMapData = null;
				File logFileName = new File(workDir, mapFileName + GEN_LOG_EXT);
				FileHandler fh = null;
				// configure log path
				try {

					FileOutputStream fout = new FileOutputStream(logFileName);
					fout.write((new Date() + "\n").getBytes());
					fout.write((MapCreatorVersion.APP_MAP_CREATOR_FULL_NAME + "\n").getBytes());
					fout.close();
					fh = new FileHandler(logFileName.getAbsolutePath(), 10*1000*1000, 1, true);
					fh.setFormatter(new SimpleFormatter());
					fh.setLevel(Level.ALL);
					Jdk14Logger jdk14Logger = new Jdk14Logger("tempLogger");
					jdk14Logger.getLogger().setLevel(Level.ALL);
					jdk14Logger.getLogger().setUseParentHandlers(false);
					jdk14Logger.getLogger().addHandler(fh);
					warningsAboutMapData = jdk14Logger;
				} catch (SecurityException e1) {
					e1.printStackTrace();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				if (fh != null) {
					LogManager.getLogManager().getLogger("").addHandler(fh);
				}
				try {
					indexCreator.generateIndexes(file, new ConsoleProgressImplementation(1), null, mapZooms,
							new MapRenderingTypesEncoder(renderingTypesFile, file.getName()), warningsAboutMapData);
				} finally {
					if (fh != null) {
						fh.close();
						LogManager.getLogManager().getLogger("").removeHandler(fh);
					}
				}
				File generated = new File(workDir, mapFileName);
				File dest = new File(indexDirFiles, generated.getName());
				if (!generated.renameTo(dest)) {
					FileOutputStream fout = new FileOutputStream(dest);
					FileInputStream fin = new FileInputStream(generated);
					Algorithms.streamCopy(fin, fout);
					fin.close();
					fout.close();
				}
				File copyLog = new File(indexDirFiles, logFileName.getName());
				FileOutputStream fout = new FileOutputStream(copyLog);
				FileInputStream fin = new FileInputStream(logFileName);
				Algorithms.streamCopy(fin, fout);
				fin.close();
				fout.close();
				//	logFileName.renameTo(new File(indexDirFiles, logFileName.getName()));

			} catch (Exception e) {
				log.error("Exception generating indexes for " + file.getName(), e); //$NON-NLS-1$
			}
		} catch (OutOfMemoryError e) {
			System.gc();
			log.error("OutOfMemory", e);

		}
		System.gc();
	}

	protected File[] getSortedFiles(File dir){
		File[] listFiles = dir.listFiles();
		Arrays.sort(listFiles, new Comparator<File>(){
			@Override
			public int compare(File o1, File o2) {
				return o1.getName().compareTo(o2.getName());
			}
		});
		return listFiles;
	}
}
