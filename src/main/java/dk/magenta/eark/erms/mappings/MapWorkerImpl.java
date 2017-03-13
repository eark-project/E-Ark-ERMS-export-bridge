package dk.magenta.eark.erms.mappings;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.magenta.eark.erms.Constants;
import dk.magenta.eark.erms.Utils;
import dk.magenta.eark.erms.db.DatabaseConnectionStrategy;
import dk.magenta.eark.erms.db.JDBCConnectionStrategy;
import dk.magenta.eark.erms.exceptions.ErmsIOException;
import dk.magenta.eark.erms.system.PropertiesHandler;
import dk.magenta.eark.erms.system.PropertiesHandlerImpl;

/**
 * @author lanre.
 */
public class MapWorkerImpl implements MapWorker {
	private final Logger logger = LoggerFactory.getLogger(MapWorkerImpl.class);
	private DatabaseConnectionStrategy dbConnectionStrategy;
	private String mapRoot;

	public MapWorkerImpl() {
		try {
			PropertiesHandler propertiesHandler = new PropertiesHandlerImpl("settings.properties");
			this.dbConnectionStrategy = new JDBCConnectionStrategy(propertiesHandler);
			this.mapRoot = replaceTokens(propertiesHandler.getProperty("mapping.root"));
			Utils.checkDirExists(this.mapRoot, true);
			System.out.print("Map root located at: " + mapRoot);
		} catch (Exception sqe) {
			System.out.println("====> Error <====\nUnable to initialise mapping worker due to: " + sqe.getMessage());
			logger.error("====> Error <====\nUnable to initialise mapping worker due to: " + sqe.getMessage());
		}
	}

	@Override
	public void saveMapping(String mappingName, File mapFile, FormDataContentDisposition fileMetadata) throws FileAlreadyExistsException, IOException, SQLException {
				
				// Move the uploaded mapping XML file from the temp dir to the final destination
		
				Path source = mapFile.toPath();
				Path target = Paths.get(mapRoot, source.getFileName().toString());
				Files.move(source, target);
				
				// After moving let's store details that allow us to retrieve it
				// later to the db

				try {
					dbConnectionStrategy.saveMapping(mappingName, target.toString(), fileMetadata); 
				} catch (SQLException e) {
					// if we didn't manage to save the information then remove
					// the file
					Files.delete(target);
					e.printStackTrace();
					throw e;
				}
	}

	/**
	 * Gets the Json object representing the requested mapping from the db
	 * 
	 * @param mappingName
	 * @return
	 */
	@Override
	public JsonObject getMapping(String mappingName) {
		JsonObjectBuilder jsonObjectBuilder = Json.createObjectBuilder();
		try {
			Mapping mp = this.dbConnectionStrategy.getMapping(mappingName);
			if (!mp.isEmpty) {
				jsonObjectBuilder.add("mapping", mp.toJsonObject());
				jsonObjectBuilder.add(Constants.SUCCESS, true);
			}
		} catch (Exception ge) {
			ge.printStackTrace();
		}
		return jsonObjectBuilder.build();
	}

	/**
	 * Returns a mapping Object. Not advisable for use in JAX-RS resources
	 *
	 * @param mapName
	 * @return a Map object or a Map.EMPTY object if it can not find a mapping
	 *         object from the db
	 */
	@Override
	public Mapping getMappingObject(String mapName) {
		try {
			return this.dbConnectionStrategy.getMapping(mapName);
		} catch (Exception ge) {
			ge.printStackTrace();
			return Mapping.EMPTY_MAP;
		}
	}

	/**
	 * Return a list of mappings on the system
	 *
	 * @return
	 */
	@Override
	public JsonObject getMappings() {
		JsonObjectBuilder jsonObjectBuilder = Json.createObjectBuilder();
		JsonArrayBuilder jsonArrayBuilder = Json.createArrayBuilder();
		try {
			List<Mapping> mappings = this.dbConnectionStrategy.getMappings();
			if (mappings.size() > 0) {
				mappings.forEach(t -> jsonArrayBuilder.add(t.toJsonObject()));
				jsonObjectBuilder.add("mappings", jsonArrayBuilder);
				jsonObjectBuilder.add(Constants.SUCCESS, true);
			}
		} catch (Exception ge) {
			ge.printStackTrace();
		}
		return jsonObjectBuilder.build();
	}

	@Override
	public JsonObject deleteMapping(String mappingName) {
		JsonObjectBuilder jsonObjectBuilder = Json.createObjectBuilder();
		try {
			Mapping mp = this.dbConnectionStrategy.getMapping(mappingName);
			if (!mp.isEmpty) {
				boolean res = this.dbConnectionStrategy.deleteMapping(mappingName);
				if (res) {
					jsonObjectBuilder.add("dbEntryDeleted", true);
				}
				// TODO: clean up orphaned files
				// Not a critical error though we would need to be able to clean
				// up "orphaned" files at some point
				File mappingFile = new File(mp.getSyspath());
				if (mappingFile.exists() && mappingFile.delete()) {
					jsonObjectBuilder.add("fileDeleted", true);
				}
			} else {
				jsonObjectBuilder.add(Constants.ERRORMSG, "Unable to instantiate mapping object before deletion");
				jsonObjectBuilder.add(Constants.SUCCESS, false);
			}
		} catch (Exception ge) {
			ge.printStackTrace();
			jsonObjectBuilder.add(Constants.ERRORMSG, "Unable to delet mapping");
			jsonObjectBuilder.add(Constants.SUCCESS, false);
		}
		return jsonObjectBuilder.build();
	}

	private String replaceTokens(String token) {
		String tmp = StringUtils.substringAfterLast(token, "}");
		String tokenStr = System.getProperty(StringUtils.substringBetween(token, "${", "}"));
		return tokenStr + tmp;
	}
}
