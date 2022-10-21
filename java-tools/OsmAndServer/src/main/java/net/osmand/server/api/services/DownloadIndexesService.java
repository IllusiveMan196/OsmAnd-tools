package net.osmand.server.api.services;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.gson.Gson;

import net.osmand.IndexConstants;
import net.osmand.util.Algorithms;

@Service
public class DownloadIndexesService  {
	
	private static final Log LOGGER = LogFactory.getLog(DownloadIndexesService.class);

	private static final String INDEX_FILE = "indexes.xml";
	private static final String DOWNLOAD_SETTINGS = "api/settings.json";
	private static final String INDEX_FILE_EXTERNAL_URL = "index-source.info";
    private static final String EXTERNAL_URL = "public-api-indexes/";
	
	@Value("${osmand.files.location}")
    private String pathToDownloadFiles;
	
	@Value("${osmand.gen.location}")
	private String pathToGenFiles;
	
	@Value("${osmand.web.location}")
    private String websiteLocation;

	private DownloadProperties settings;

	private Gson gson;
	
	public DownloadIndexesService() {
		gson = new Gson();
	}
	
	public DownloadProperties getSettings() {
		if(settings == null) {
			reloadConfig(new ArrayList<String>());
		}
		return settings;
	}
	
	public boolean reloadConfig(List<String> errors) {
    	try {
    		DownloadProperties s = gson.fromJson(new FileReader(new File(websiteLocation, DOWNLOAD_SETTINGS)), DownloadProperties.class);
    		s.prepare();
    		settings = s;
    	} catch (IOException ex) {
    		if(errors != null) {
    			errors.add(DOWNLOAD_SETTINGS + " is invalid: " + ex.getMessage());
    		}
            LOGGER.warn(ex.getMessage(), ex);
            return false;
    	}
        return true;
    }
	
	

	// 15 minutes
	@Scheduled(fixedDelay = 1000 * 60 * 15)
	public void checkOsmAndLiveStatus() {
		generateStandardIndexFile();
	}
	
	public DownloadIndexDocument loadDownloadIndexes() {
		DownloadIndexDocument doc = new DownloadIndexDocument();
		File rootFolder = new File(pathToDownloadFiles);
		loadIndexesFromDir(doc.getMaps(), rootFolder, DownloadType.MAP);
		loadIndexesFromDir(doc.getVoices(), rootFolder, DownloadType.VOICE);
		loadIndexesFromDir(doc.getFonts(), rootFolder, DownloadType.FONTS);
		loadIndexesFromDir(doc.getInapps(), rootFolder, DownloadType.DEPTH);
		loadIndexesFromDir(doc.getDepths(), rootFolder, DownloadType.DEPTHMAP);
		loadIndexesFromDir(doc.getWikimaps(), rootFolder, DownloadType.WIKIMAP);
		loadIndexesFromDir(doc.getTravelGuides(), rootFolder, DownloadType.TRAVEL);
		loadIndexesFromDir(doc.getRoadMaps(), rootFolder, DownloadType.ROAD_MAP);
		loadIndexesFromDir(doc.getSrtmMaps(), rootFolder, DownloadType.SRTM_MAP.getPath(), DownloadType.SRTM_MAP, IndexConstants.BINARY_SRTM_MAP_INDEX_EXT);
		loadIndexesFromDir(doc.getSrtmFeetMaps(), rootFolder, DownloadType.SRTM_MAP.getPath(), DownloadType.SRTM_MAP, IndexConstants.BINARY_SRTM_FEET_MAP_INDEX_EXT);
		loadIndexesFromDir(doc.getHillshade(), rootFolder, DownloadType.HILLSHADE);
		loadIndexesFromDir(doc.getSlope(), rootFolder, DownloadType.SLOPE);
		loadIndexesFromDir(doc.getHeightmap(), rootFolder, DownloadType.HEIGHTMAP);
		return doc;
	}
	
	public String getFilePathUrl(String name) throws IOException {
		DownloadIndexDocument doc = getIndexesDocument(false, false);
		// ignore folders for srtm / hillshade / slope
		if (name.lastIndexOf('/') != -1) {
			name = name.substring(name.lastIndexOf('/') + 1);
		}
		String dwName;
		if (name.endsWith("obf")) {
			// add _2 for obf files 
			int ind = name.indexOf('.');
			dwName = name.substring(0, ind) + "_2" + name.substring(ind);
		} else {
			// replace ' ' as it could be done on device 
			dwName = name.replace(' ', '_');
		}
		for (DownloadIndex di : doc.getAllMaps()) {
			if (di.getName().equals(dwName) || di.getName().equals(dwName + ".zip")) {
				LOGGER.info(di.getName());
				File file = new File(pathToDownloadFiles, dwName + ".zip");
				if (!file.exists()) {
					file = new File(pathToDownloadFiles, di.getDownloadType().getPath() + "/" + dwName + ".zip");
				}
				if (!file.exists()) {
					file = new File(pathToDownloadFiles, dwName);
				}
				if (!file.exists()) {
					file = new File(pathToDownloadFiles, di.getDownloadType().getPath() + "/" + dwName);
				}
				if (file.exists()) {
					return file.getAbsolutePath();
				}
				DownloadProperties servers = getSettings();
				DownloadServerSpecialty sp = DownloadServerSpecialty.getSpecialtyByDownloadType(di.getDownloadType());
				if (sp != null) {
					String host = servers.getServer(sp);
					LOGGER.info(di.getName() + " " + sp + " " + host);
					if (host != null && Algorithms.isEmpty(host)) {
						try {
							String pm = "";
							if (sp.httpParams.length > 0) {
								pm = "&" + sp.httpParams[0] + "=yes";
							}
							String urlRaw = "https://" + host + "/download?file=" + di.getName() + pm;
							LOGGER.info(di.getName() + " " + sp + " " + urlRaw);
							URL url = new URL(urlRaw);
							HttpURLConnection con = (HttpURLConnection) url.openConnection();
							con.setRequestMethod("HEAD");
							con.setDoOutput(false);
							int code = con.getResponseCode();
							con.disconnect();
							if (code >= 200 && code < 400) {
								return urlRaw;
							}
						} catch (IOException e) {
							LOGGER.error("Error checking existing index: " + e.getMessage(), e);
						}
						return null;
					}
				}
			}
		}
		return null;
	}
	
	public File getIndexesXml(boolean upd, boolean gzip) {
		File target = getStandardFilePath(gzip);
		if (!target.exists() || upd) {
			generateStandardIndexFile();
		}
		return target;
	}
	
	public DownloadIndexDocument getIndexesDocument(boolean upd, boolean gzip) throws IOException {
		File target = getIndexesXml(upd, gzip);
		return unmarshallIndexes(target);
	}


	private File getStandardFilePath(boolean gzip) {
		return new File(pathToGenFiles, gzip ? INDEX_FILE + ".gz" : INDEX_FILE);
	}
	
	private synchronized void generateStandardIndexFile() {
		long start = System.currentTimeMillis();
		DownloadIndexDocument di = loadDownloadIndexes();
		File target = getStandardFilePath(false);
		generateIndexesFile(di, target, start);
		File gzip = getStandardFilePath(true);
		gzipFile(target, gzip);
		LOGGER.info(String.format("Regenerate indexes.xml in %.1f seconds",
				((System.currentTimeMillis() - start) / 1000.0)));
	}

	private void gzipFile(File target, File gzip) {
		try {
			FileInputStream is = new FileInputStream(target);
			GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(gzip));
			Algorithms.streamCopy(is, out);
			is.close();
			out.close();
		} catch (IOException e) {
			LOGGER.error("Gzip file " + target.getName(), e);
			e.printStackTrace();
		}
	}

	private void loadIndexesFromDir(List<DownloadIndex> list, File rootFolder, DownloadType type) {
		if(type == DownloadType.MAP) {
			loadIndexesFromDir(list, rootFolder, ".", type, null);
		}
		loadIndexesFromDir(list, rootFolder, type.getPath(), type, null);
	}
	
	private void loadIndexesFromDir(List<DownloadIndex> list, File rootFolder, String subPath, DownloadType type, String filterFiles) {
		File subFolder = new File(rootFolder, subPath);
		File[] files = subFolder.listFiles();
		if(files == null || files.length == 0) {
			return;
		}
		if (files.length > 0 && files[0].getName().equals(INDEX_FILE_EXTERNAL_URL)) {
            try {
                String host;
                BufferedReader bufferreader = new BufferedReader(new FileReader(files[0]));
                while ((host = bufferreader.readLine()) != null) {
                    URL url = new URL("https://" + host + "/" + EXTERNAL_URL + subPath);
                    InputStreamReader reader = new InputStreamReader(url.openStream());
                    ExternalSource [] externalSources = gson.fromJson(reader, ExternalSource[].class);
                    if (externalSources.length > 0) {
                        boolean areFilesAdded = false;
                        for (ExternalSource source : externalSources) {
                            // do not read external zip files, otherwise it will be too long by remote connection
                            if (source.type.equals("file") && type.acceptFileName(source.name) && !isZip(source.name)) {
                                DownloadIndex di = new DownloadIndex();
                                di.setType(type);
                                String name = source.name;
                                int extInd = name.indexOf('.');
                                String ext = name.substring(extInd + 1);
                                formatName(name, extInd);
                                di.setName(name);
                                di.setSize(source.size);
                                di.setContainerSize(source.size);
                                di.setTimestamp(source.getTimestamp());
                                di.setDate(source.getTimestamp());
                                di.setContentSize(source.size);
                                di.setTargetsize(source.size);
                                di.setDescription(type.getDefaultTitle(name, ext));
                                list.add(di);
                                areFilesAdded = true;
                            }
                        }
                        if (areFilesAdded) {
                            break;
                        }
                    }
                    // will continue if was not find any files in this host (server)
                }
                bufferreader.close();
            } catch (IOException e) {
                LOGGER.error("LOAD EXTERNAL INDEXES: " + e.getMessage(), e.getCause());
            }
            return;
        }
		for (File lf : files) {
			if (filterFiles != null && !lf.getName().contains(filterFiles)) {
				continue;
			} else if (type.acceptFileName(lf.getName())) {
				String name = lf.getName();
                int extInd = name.indexOf('.');
                String ext = name.substring(extInd + 1);
				formatName(name, extInd);
				DownloadIndex di = new DownloadIndex();
				di.setType(type);
				di.setName(lf.getName());
				di.setSize(lf.length());
				di.setContainerSize(lf.length());
				if (isZip(lf)) {
					try {
						ZipFile zipFile = new ZipFile(lf);
						long contentSize = zipFile.stream().mapToLong(ZipEntry::getSize).sum();
						di.setContentSize(contentSize);
						di.setTargetsize(contentSize);
						Enumeration<? extends ZipEntry> entries = zipFile.entries();
						if (entries.hasMoreElements()) {
							ZipEntry entry = entries.nextElement();
							long mtime = entry.getLastModifiedTime().to(TimeUnit.MILLISECONDS);
							di.setTimestamp(mtime);
							di.setDate(mtime);
							String description = entry.getComment();
							if (description != null) {
								di.setDescription(description);
							} else {
								di.setDescription(type.getDefaultTitle(name, ext));
							}
						}
						list.add(di);
						zipFile.close();
					} catch (Exception e) {
						LOGGER.error(lf.getName() + ": " + e.getMessage(), e);
						e.printStackTrace();
					}
				} else {
					di.setTimestamp(lf.lastModified());
					di.setDate(lf.lastModified());
					di.setContentSize(lf.length());
					di.setTargetsize(lf.length());
					di.setDescription(type.getDefaultTitle(name, ext));
					list.add(di);
				}
			}
		}
	}

	protected boolean isZipValid(File file) {
		boolean isValid = true;
		if (isZip(file)) {
			try {
				ZipFile fl = new ZipFile(file);
				fl.close();
			} catch (IOException ex) {
				isValid = false;
			}
		}
		return isValid;
	}

	private boolean isZip(File file) {
		return file.getName().endsWith(".zip");
	}

    private boolean isZip(String fileName) {
        return fileName.endsWith(".zip");
    }

	private void generateIndexesFile(DownloadIndexDocument doc, File file, long start) {
		try {
			JAXBContext jc = JAXBContext.newInstance(DownloadIndexDocument.class);
			Marshaller marshaller = jc.createMarshaller();
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
			doc.setMapVersion(1);
			doc.setTimestamp(new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date()));
			doc.setGentime(String.format("%.1f",
					((System.currentTimeMillis() - start) / 1000.0)));
			marshaller.marshal(doc, file);
		} catch (JAXBException ex) {
			LOGGER.error(ex.getMessage(), ex);
			ex.printStackTrace();
		}
	}
	
	public enum DownloadType {
	    MAP("indexes"),
	    VOICE("indexes") ,
	    DEPTH("indexes/inapp/depth") ,
	    DEPTHMAP("depth") ,
	    FONTS("indexes/fonts") ,
	    WIKIMAP("wiki") ,
	    TRAVEL("travel") ,
	    ROAD_MAP("road-indexes") ,
	    HILLSHADE("hillshade"),
	    HEIGHTMAP("heightmap"),
	    SLOPE("slope") ,
	    SRTM_MAP("srtm-countries") ;


		private final String path;

		DownloadType(String path) {
			this.path = path;
		}
		
		public String getPath() {
			return path;
		}


        public boolean acceptFileName(String fileName) {
            switch (this) {
                case HEIGHTMAP:
                    return fileName.endsWith(".sqlite");
                case TRAVEL:
                    return fileName.endsWith(".travel.obf.zip") || fileName.endsWith(".travel.obf");
                case MAP:
                case ROAD_MAP:
                case WIKIMAP:
                case DEPTH:
                case DEPTHMAP:
                case SRTM_MAP:
                    return fileName.endsWith(".obf.zip") || fileName.endsWith(".obf") || fileName.endsWith(".extra.zip");
                case HILLSHADE:
                case SLOPE:
                    return fileName.endsWith(".sqlitedb");
                case FONTS:
                    return fileName.endsWith(".otf.zip");
                case VOICE:
                    return fileName.endsWith(".voice.zip");
            }
            return false;
        }


		public String getDefaultTitle(String regionName, String ext) {
			switch (this) {
			case MAP:
				return String.format("Map, Roads, POI, Transport, Address data for %s", regionName);
			case ROAD_MAP:
				return String.format("Roads, POI, Address data for %s", regionName);
			case WIKIMAP:
				return String.format("Wikipedia POI data for %s", regionName);
			case DEPTH:
			case DEPTHMAP:
				return String.format("Depth maps for %s", regionName);
			case SRTM_MAP:
				String suf = ext.contains("srtmf") ? "feet" : "meters";
				return String.format("Contour lines (%s) for %s", suf, regionName);
			case TRAVEL:
				return String.format("Travel for %s", regionName);
			case HEIGHTMAP:
				return String.format("%s", regionName);
			case HILLSHADE:
				return String.format("%s", regionName);
			case SLOPE:
				return String.format("%s", regionName);
			case FONTS:
				return String.format("Fonts %s", regionName);
			case VOICE:
				return String.format("Voice package: %s", regionName);
			}
			return "";
		}

	    public String getType() {
	    	return name().toLowerCase();
	    }
	}
	
	
	public static void main(String[] args) {
		// small test
		DownloadProperties dp = new DownloadProperties();
		String key = DownloadServerSpecialty.OSMLIVE.toString().toLowerCase();
		dp.servers.put(key, new HashMap<>());
		dp.servers.get(key).put("dl1", 1);
		dp.servers.get(key).put("dl2", 1);
		dp.servers.get(key).put("dl3", 3);
		dp.prepare();
		System.out.println(dp.getPercent(DownloadServerSpecialty.OSMLIVE, "dl1"));
		System.out.println(dp.getPercent(DownloadServerSpecialty.OSMLIVE, "dl2"));
		System.out.println(dp.getPercent(DownloadServerSpecialty.OSMLIVE, "dl3"));
		Map<String, Integer> cnts = new TreeMap<String, Integer>();
		for(String s : dp.serverNames) {
			cnts.put(s, 0);
		}
		for(int i = 0; i < 1000; i ++) {
			String s = dp.getServer(DownloadServerSpecialty.OSMLIVE);
			cnts.put(s, cnts.get(s) + 1);
		}
		System.out.println(cnts);
	}
	
	private static class DownloadServerCategory {
	
		Map<String, Integer> percents = new TreeMap<>();
		int sum;
		String[] serverNames;
		int[] bounds;
	}
	
	public enum DownloadServerSpecialty {
		MAIN(new String[0], DownloadType.VOICE, DownloadType.FONTS, DownloadType.MAP),
		SRTM("srtmcountry", DownloadType.SRTM_MAP),
		HILLSHADE("hillshade", DownloadType.HILLSHADE),
		SLOPE("slope", DownloadType.SLOPE),
		HEIGHTMAP("heightmap", DownloadType.HEIGHTMAP),
		OSMLIVE(new String[] {"aosmc", "osmc"}, DownloadType.MAP),
		DEPTH("depth", DownloadType.DEPTH, DownloadType.DEPTHMAP),
		WIKI(new String[] {"wikivoyage", "wiki", "travel"}, DownloadType.WIKIMAP, DownloadType.TRAVEL),
		ROADS("road", DownloadType.ROAD_MAP);
		
		public final DownloadType[] types;
		public final String[] httpParams;

		DownloadServerSpecialty(String httpParam, DownloadType... tp) {
			this.httpParams = new String[] {httpParam};
			this.types = tp;
		}
		
		DownloadServerSpecialty(String[] httpParams, DownloadType... tp) {
			this.httpParams = httpParams;
			this.types = tp;
		}
		
		public static DownloadServerSpecialty getSpecialtyByDownloadType(DownloadType c) {
			for(DownloadServerSpecialty s : values())  {
				if(s.types == null) {
					continue;
				}
				for(DownloadType t : s.types) {
					if(t == c) {
						return s;
					}
				}
			}
			return null;
		}
		
	}
	
	public static class DownloadProperties {
		public final static String SELF = "self";
		
		Set<String> serverNames = new TreeSet<String>();
		DownloadServerCategory[] cats = new DownloadServerCategory[DownloadServerSpecialty.values().length];
		Map<String, Map<String, Integer>> servers = new TreeMap<>();
		
		public void prepare() {
			for(DownloadServerSpecialty s : DownloadServerSpecialty.values()) {
				Map<String, Integer> mp = servers.get(s.name().toLowerCase());
				prepare(s, mp == null ? Collections.emptyMap() : mp);
			}
		}
		
		public Set<String> getServers() {
			return serverNames;
		}
		
		private void prepare(DownloadServerSpecialty tp, Map<String, Integer> mp) {
			serverNames.addAll(mp.keySet());
			DownloadServerCategory cat = new DownloadServerCategory();
			cats[tp.ordinal()] = cat;
			for(Integer i : mp.values()) {
				cat.sum += i;
			}
			if(cat.sum > 0) {
				int ind = 0;
				cat.bounds = new int[mp.size()];
				cat.serverNames = new String[mp.size()];
				for(String server : mp.keySet()) {
					cat.serverNames[ind] = SELF.equals(server) ? null : server;
					cat.bounds[ind] = mp.get(server);
					cat.percents.put(server, 100 * mp.get(server) / cat.sum);
					ind++;
				}
			} else {
				cat.bounds = new int[0];
				cat.serverNames = new String[0];
			}
			
		}

		public int getPercent(DownloadServerSpecialty type, String s) {
			Integer p = cats[type.ordinal()].percents.get(s);
			if(p == null) {
				return 0;
			}
			return p.intValue();
		}
		
		
		public String getServer(DownloadServerSpecialty type) {
			DownloadServerCategory cat = cats[type.ordinal()];
			if (cat.sum > 0) {
				ThreadLocalRandom tlr = ThreadLocalRandom.current();
				int val = tlr.nextInt(cat.sum);
				for(int i = 0; i < cat.bounds.length; i++) {
					if(val >= cat.bounds[i]) {
						val -= cat.bounds[i];
					} else {
						return cat.serverNames[i];
					}
				}
			}
			return null;
		}

	}
	
	private DownloadIndexDocument unmarshallIndexes(File fl) throws IOException {
		try {
			JAXBContext jc = JAXBContext.newInstance(DownloadIndexDocument.class);
			Unmarshaller unmarshaller = jc.createUnmarshaller();
			DownloadIndexDocument did = (DownloadIndexDocument) unmarshaller.unmarshal(fl);
			did.prepareMaps();
			return did;
		} catch (JAXBException ex) {
			LOGGER.error(ex.getMessage(), ex);
			throw new IOException(ex);
		}
	}

	private void formatName(String name, int extInd) {
        name = name.substring(0, extInd);
        if (name.endsWith("_ext_2")) {
            name = name.replace("_ext_2", "");
        }
        if (name.endsWith("_2")) {
            name = name.replace("_2", "");
        }
        name = name.replace('_', ' ');
    }

    public static class ExternalSource {
	    private String name;
	    private String type;
	    private String mtime;
	    private long size;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getMtime() {
            return mtime;
        }

        public void setMtime(String mtime) {
            this.mtime = mtime;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public long getTimestamp() {
            //example Wed, 31 Aug 2022 11:53:18 GMT
            DateFormat format = new SimpleDateFormat("EEE, d MMM yyyy hh:mm:ss zzz");
            try {
                Date date = format.parse(mtime);
                return date.getTime();
            } catch (ParseException e) {
                LOGGER.error("LOAD EXTERNAL INDEXES, problem parse of date: \"" + mtime + "\"" + e.getMessage(), e.getCause());
            }
            return -1;
        }
    }
}
