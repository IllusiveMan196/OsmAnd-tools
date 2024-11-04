package net.osmand.server.controllers.pub;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import net.osmand.server.Application;
import net.osmand.server.tileManager.TileMemoryCache;
import net.osmand.server.tileManager.TileServerConfig;
import net.osmand.server.tileManager.GeotiffTile;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import net.osmand.server.api.services.OsmAndMapsService;

@Controller
@RequestMapping("/heightmap")
public class GeotiffTileController {

	protected static final Log LOGGER = LogFactory.getLog(GeotiffTileController.class);

	private static final String HILLSHADE_TYPE = "hillshade";
	private static final String SLOPE_TYPE = "slope";
	private static final String HEIGHT_TYPE = "height";

	private static final long CLEANUP_CACHE_AFTER_ZOOM_7 = TimeUnit.DAYS.toMillis(7);
	private static final long CLEANUP_CACHE_BEFORE_ZOOM_7 = TimeUnit.DAYS.toMillis(100);
	private static final long CLEANUP_INTERVAL_MILLIS = 12 * 60 * 60 * 1000L; // 12 hours

	@Autowired
	OsmAndMapsService osmAndMapsService;

	@Autowired
	TileServerConfig config;

	@Value("${osmand.heightmap.location}")
	String geotiffTiles;

	private final TileMemoryCache<GeotiffTile> tileMemoryCache = new TileMemoryCache<>();

	private ResponseEntity<?> errorConfig(String msg) {
		return ResponseEntity.badRequest()
				.body(msg);
	}

	@RequestMapping(path = "/{type}/{z}/{x}/{y}.png", produces = MediaType.IMAGE_PNG_VALUE)
	public ResponseEntity<?> getTile(@PathVariable String type, @PathVariable int z, @PathVariable int x, @PathVariable int y)
			throws IOException {
		if (!osmAndMapsService.validateAndInitConfig()) {
			return errorConfig("Tile service is not initialized");
		}
		if (osmAndMapsService.validateNativeLib() != null) {
			return errorConfig("Tile service is not initialized: " + osmAndMapsService.validateNativeLib());
		}

		TileType tileType = TileType.from(type);
		String tileId = config.createTileId(tileType.getType(), x, y, z, -1, -1);
		GeotiffTile tile = tileMemoryCache.getTile(tileId, k -> new GeotiffTile(config, tileType, x, y, z));
		// for testing
		// GeotiffTile tile = new GeotiffTile(config, tileType, x, y, z);
		tileMemoryCache.cleanupCache();
		BufferedImage img = tile.getCacheRuntimeImage();
		tile.touch();
		if (img == null) {
			img = getTileFromService(tile);
		}
		if (img == null) {
			return ResponseEntity.badRequest().body("Failed to get tile");
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ImageIO.write(img, "png", baos);
		return ResponseEntity.ok()
				.header("Cache-Control", "public, max-age=2592000")
				.body(new ByteArrayResource(baos.toByteArray()));
	}

	@Scheduled(fixedRate = CLEANUP_INTERVAL_MILLIS)
	public synchronized void cleanUpCache() {
		File cacheDir = new File(config.heightmapLocation);
		if (!cacheDir.exists() || !cacheDir.isDirectory()) {
			return; // Nothing to clean up
		}
		cleanUpDirectory(cacheDir);
	}

	private void cleanUpDirectory(File dir) {
		long now = System.currentTimeMillis();
		File[] files = dir.listFiles();

		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					cleanUpDirectory(file);
				} else if (file.isFile() && isValidHeightmapFile(file)) {
					int zoom = parseZoomFromFileName(file.getName());
					if (zoom == -1) {
						continue;
					}
					if ((zoom <= 7 && now - file.lastModified() >= CLEANUP_CACHE_BEFORE_ZOOM_7) ||
							(zoom > 7 && now - file.lastModified() >= CLEANUP_CACHE_AFTER_ZOOM_7)) {
						try {
							Path filePath = file.toPath();
							Files.delete(filePath);
						} catch (IOException e) {
							LOGGER.warn("Failed to delete file: " + file.getAbsolutePath(), e);
						}

					}
				}
			}
		}
	}

	private int parseZoomFromFileName(String fileName) {
		String[] parts = fileName.split(File.separator);
		if (parts.length < 2) {
			return -1;
		}
		for (int i = 0; i < parts.length; i++) {
			if (parts[i].equals("heightmaps") && i + 2 < parts.length) {
				return Integer.parseInt(parts[i + 2]);
			}
		}
		return -1;
	}

	private boolean isValidHeightmapFile(File file) {
		String name = file.getName();
		return name.endsWith(".png") && name.contains("heightmaps");
	}

	private BufferedImage getTileFromService(GeotiffTile tile) throws IOException {
		Path tempDirectory = Application.getColorPaletteDirectory();
		if (tempDirectory == null) {
			throw new IOException("Temporary directory with color palettes is not initialized");
		}

		File resultColorsFile = new File(tempDirectory.toFile(), tile.getTileType().getResultColorFilePath(""));
		File intermediateColorsFile = new File(tempDirectory.toFile(), tile.getTileType().getIntermediateColorFilePath(""));

		String resultColorsResourcePath = resultColorsFile.exists() ? resultColorsFile.getAbsolutePath() : "";
		String intermediateColorsResourcePath = intermediateColorsFile.exists() ? intermediateColorsFile.getAbsolutePath() : "";

		BufferedImage img = osmAndMapsService.renderGeotiffTile(
				geotiffTiles,
				resultColorsResourcePath,
				intermediateColorsResourcePath,
				tile.getTileType().getResType(),
				256, tile.z, tile.x, tile.y
		);
		File cacheFile = tile.getCacheFile(".png");
		if (cacheFile == null) {
			return null;
		}
		tile.setRuntimeImage(img);
		tile.saveImageToCache(tile, cacheFile);
		return img;
	}

	public enum TileType {
		HILLSHADE(1, "hillshade_main_default.txt", "hillshade_color_default.txt"),
		SLOPE(2, "slope_default.txt", ""),
		HEIGHT(3, "height_altitude_default.txt", "");

		private final int resType;
		private final String resultFile;
		private final String intermediateFile;

		TileType(int resType, String resultFile, String intermediateFile) {
			this.resType = resType;
			this.resultFile = resultFile;
			this.intermediateFile = intermediateFile;
		}

		public int getResType() {
			return resType;
		}

		public String getResultColorFilePath(String basePath) {
			return basePath + resultFile;
		}

		public String getIntermediateColorFilePath(String basePath) {
			return intermediateFile.isEmpty() ? "" : basePath + intermediateFile;
		}

		public String getType() {
			return switch (this) {
				case HILLSHADE -> HILLSHADE_TYPE;
				case SLOPE -> SLOPE_TYPE;
				case HEIGHT -> HEIGHT_TYPE;
			};
		}

		public static TileType from(String type) {
			return switch (type.toLowerCase()) {
				case HILLSHADE_TYPE -> HILLSHADE;
				case SLOPE_TYPE -> SLOPE;
				case HEIGHT_TYPE -> HEIGHT;
				default -> throw new IllegalArgumentException("Unknown type: " + type);
			};
		}
	}
}
