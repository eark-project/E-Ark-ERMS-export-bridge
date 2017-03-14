package dk.magenta.eark.erms.extraction;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Singleton;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.jdom2.JDOMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.magenta.eark.erms.Constants;
import dk.magenta.eark.erms.exceptions.ErmsRuntimeException;
import dk.magenta.eark.erms.json.JsonUtils;
import dk.magenta.eark.erms.repository.CmisSessionWorker;
import dk.magenta.eark.erms.repository.CmisSessionWorkerImpl;
import dk.magenta.eark.erms.repository.RepositoryResource;
import dk.magenta.eark.erms.repository.profiles.Profile;
import dk.magenta.eark.erms.xml.XmlHandler;
import dk.magenta.eark.erms.xml.XmlHandlerImpl;

@Path("extraction")
@Singleton
public class ExtractionResource {

	private final Logger logger = LoggerFactory.getLogger(RepositoryResource.class);
	private static Map<String, CmisSessionWorker> connectionPool = new HashMap<>();

	private static ExecutorService executorService;
	private static Future future;
	private static ExtractionWorker extractionWorker;
	private static String eadTemplate;

	static final String NOT_RUNNING = "NOT_RUNNING";
	static final String RUNNING = "RUNNING";
	static final String DONE = "DONE";

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Path("extract")
	public JsonObject extract(JsonObject json) {

		JsonObjectBuilder builder = Json.createObjectBuilder();

		// TODO: the JsonUtils methods below should not take a builder as an
		// argument...

		// Check if the mandatory keys are in the request JSON
		String[] mandatoryJsonKeys = { Profile.NAME, Constants.MAP_NAME, Constants.EXPORT_LIST,
				Constants.EXCLUDE_LIST };
		if (!JsonUtils.containsCorrectKeys(json, mandatoryJsonKeys)) {
			JsonUtils.addKeyErrorMessage(builder, mandatoryJsonKeys);
			return builder.build();
		}

		// Check that the profile name, the mapping name and the export path are
		// not blank
		if (!StringUtils.isNotBlank(json.getString(Profile.NAME))
				|| !StringUtils.isNotBlank(json.getString(Constants.MAP_NAME))) {
			builder.add(Constants.SUCCESS, false);
			builder.add(Constants.ERRORMSG, "Blank values are not allowed in the request JSON");
			return builder.build();
		}

		// Check that the exportList is a none-empty array
		if (!JsonUtils.isArrayNoneEmpty(json, Constants.EXPORT_LIST)) {
			JsonUtils.addArrayErrorMessage(builder, Constants.EXPORT_LIST);
			return builder.build();
		}

		// Check that the excludeList is an array
		if (!JsonUtils.isArray(json, Constants.EXCLUDE_LIST)) {
			JsonUtils.addArrayErrorMessage(builder, Constants.EXCLUDE_LIST);
			return builder.build();
		}
		
		// Check that the user has uploaded an EAD template file
		if (eadTemplate == null) {
			JsonUtils.addErrorMessage(builder, "No EAD template file uploaded yet");
			return builder.build();
		}

		// Everything OK in the request JSON - begin extraction in new thread

		String status = checkStatus();
		if (status.equals(DONE) || status.equals(NOT_RUNNING)) {
			executorService = Executors.newFixedThreadPool(1);
			extractionWorker = new ExtractionWorker(json,
					getSessionWorker(json.getString(Profile.NAME), json.getString(Constants.MAP_NAME)), eadTemplate);
			future = executorService.submit(extractionWorker);
			executorService.shutdown();
			builder.add(Constants.SUCCESS, true);
			builder.add(Constants.MESSAGE, "Extraction initiated - check /status for error messages");
		} else if (status.equals(RUNNING)) {
			builder.add(Constants.SUCCESS, false);
			builder.add(Constants.MESSAGE, "Process already running");
		} else {
			// May be needed later
			// return null;
		}

		return builder.build();
	}

	
	// TODO: refactor - use checkStatus() instead
	@GET
	@Path("terminate")
	@Produces(MediaType.APPLICATION_JSON)
	public JsonObject terminate() {
		JsonObjectBuilder builder = Json.createObjectBuilder();
		if (future == null) {
			builder.add(Constants.SUCCESS, false);
			builder.add(Constants.MESSAGE, "No processes are running");
		} else {
			if (future.isDone()) {
				builder.add(Constants.SUCCESS, false);
				builder.add(Constants.MESSAGE, "Processes already done");
			} else {
				executorService.shutdownNow();
				future = null;
				builder.add(Constants.SUCCESS, true);
				builder.add(Constants.MESSAGE, "Process terminated");
			}
		}
		return builder.build();
	}

	
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Path("status")
	public JsonObject status() {
		String status = checkStatus();
		if (status.equals(DONE)) {
			return extractionWorker.getResponse();
		} else {
			JsonObjectBuilder builder = Json.createObjectBuilder();
			builder.add(Constants.SUCCESS, true);
			builder.add(Constants.STATUS, status);
			return builder.build();
		}
	}
	
	
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/ead/upload")
    public JsonObject uploadEad(@FormDataParam("eadFile") String eadFileName,
                                @FormDataParam("file") InputStream fileInputStream,
                                @FormDataParam("file") FormDataContentDisposition fileMetaData) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        if (StringUtils.isNotBlank(eadFileName) && fileInputStream != null) {
            try {
                //Read the file into a temp file

                /*Create a temp file we might need to use this for pre-processing before storing. e.g. validation */
                File tempFile = File.createTempFile("ead", ".xml");

                //Read the content into the temp file the java 8 way
                int rd  = (int) Files.copy(fileInputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                IOUtils.closeQuietly(fileInputStream);
                eadTemplate = tempFile.getAbsolutePath();
                
                System.out.print("\n The number of bytes read - "+ rd +" to "+tempFile.getAbsolutePath()+"\n");

                // Validate the uploaded mapping XML file
                InputStream xmlInputStream = new FileInputStream(tempFile);
                XmlHandler xmlHandler = new XmlHandlerImpl();
                try {
                    xmlHandler.readAndValidateXml(xmlInputStream, "ead3.xsd");

                    builder.add(Constants.SUCCESS, true);
                    builder.add(Constants.MESSAGE, "EAD template uploaded");

                } catch (JDOMException e) {
                    builder.add(Constants.SUCCESS, false);
                    builder.add(Constants.MESSAGE, "The uploaded EAD template is not valid according to ead.xsd");
                    builder.add("validationError", xmlHandler.getErrorMessage());
                }
                xmlInputStream.close();

            } catch (Exception e) {
                e.printStackTrace();
                builder.add(Constants.SUCCESS, false);
                builder.add(Constants.ERRORMSG, e.getMessage());
            }
        } else {
            builder.add(Constants.SUCCESS, false);
            builder.add(Constants.ERRORMSG, "Unable to persist uploaded mapping. Check system logs for details!");
        }

        return builder.build();
    }

	
	private String checkStatus() {
		String status;
		if (future == null) {
			status = NOT_RUNNING;
		} else {
			if (future.isDone()) {
				status = DONE;
			} else {
				status = RUNNING;
			}
		}
		return status;
	}

	// TODO: refactor - this method is also used in the RepositoryResource

	/**
	 * Returns a cmis session worker instance given a profile name
	 * 
	 * @param profileName
	 * @return
	 */
	private CmisSessionWorker getSessionWorker(String profileName, String mapName) {
		String connectionKey = profileName + "_" + mapName;
		try {
			if (!connectionPool.containsKey(connectionKey))
				connectionPool.put(connectionKey, new CmisSessionWorkerImpl(profileName, mapName));
		} catch (Exception ge) {
			logger.error("Unable to create session worker due to: " + ge.getMessage());
			throw new ErmsRuntimeException(ge.getMessage());
		}
		return connectionPool.get(connectionKey);
	}
}
