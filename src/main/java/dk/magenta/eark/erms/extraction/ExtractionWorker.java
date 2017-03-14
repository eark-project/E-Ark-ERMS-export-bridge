package dk.magenta.eark.erms.extraction;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.Tree;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.jdom2.Element;
import org.jdom2.JDOMException;

import dk.magenta.eark.erms.Constants;
import dk.magenta.eark.erms.db.DatabaseConnectionStrategy;
import dk.magenta.eark.erms.db.JDBCConnectionStrategy;
import dk.magenta.eark.erms.ead.EadBuilder;
import dk.magenta.eark.erms.ead.MappingParser;
import dk.magenta.eark.erms.ead.MetadataMapper;
import dk.magenta.eark.erms.json.JsonUtils;
import dk.magenta.eark.erms.mappings.Mapping;
import dk.magenta.eark.erms.repository.CmisSessionWorker;
import dk.magenta.eark.erms.system.PropertiesHandler;
import dk.magenta.eark.erms.system.PropertiesHandlerImpl;
import dk.magenta.eark.erms.xml.XmlHandler;
import dk.magenta.eark.erms.xml.XmlHandlerImpl;

// Let's not make this an interface for now
public class ExtractionWorker implements Runnable {

	private JsonObject json;
	private Session session;
	private MappingParser mappingParser;
	private MetadataMapper metadataMapper;
	private EadBuilder eadBuilder;
	private XmlHandler xmlHandler;
	private IOHandler fileExtractor;
	private Set<String> excludeList;
	private CmisPathHandler cmisPathHandler;
	private boolean removeFirstDaoElement;
	private JsonObject response;
	private Path exportPath;
	private DatabaseConnectionStrategy dbConnectionStrategy;
	private String pathToEadTemplate;
	
	public ExtractionWorker(JsonObject json, CmisSessionWorker cmisSessionWorker, String pathToEadTemplate) {
		this.json = json;
		this.pathToEadTemplate = pathToEadTemplate;
		session = cmisSessionWorker.getSession();
		metadataMapper = new MetadataMapper();
		removeFirstDaoElement = true;
		xmlHandler = new XmlHandlerImpl();
				
		// Get the export path
		PropertiesHandler propertiesHandler = new PropertiesHandlerImpl("settings.properties");
		exportPath = Paths.get(propertiesHandler.getProperty("exportPath"));
		
		// DB connection
		try {
			dbConnectionStrategy = new JDBCConnectionStrategy(propertiesHandler);
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Performs the extraction process
	 * 
	 * @param json
	 *            the request JSON
	 * @param cmisSessionWorker
	 * @return JSON object describing the result
	 */
	public void run() {

		JsonObjectBuilder builder = Json.createObjectBuilder();

		// Get the mapping
		
		String mapName = json.getString(Constants.MAP_NAME);
		try {
			Mapping	mapping = dbConnectionStrategy.getMapping(mapName);
			String mappingFile = mapping.getSyspath();
			InputStream mappingInputStream = new FileInputStream(new File(mappingFile));
			mappingParser = new MappingParser(mapName, mappingInputStream);
			mappingInputStream.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			response = JsonUtils.addErrorMessage(builder, "Mapping file not found!").build();
			return;
		} catch (java.io.IOException e) {
			e.printStackTrace();
			response = JsonUtils.addErrorMessage(builder, "An I/O error occured while handling the mapping file!").build();
			return;
		} catch (SQLException e) {
			e.printStackTrace();
			response = JsonUtils.addErrorMessage(builder, "The was a problem getting the mapping profile from the DB!").build();
			return;
		}

		// Load the excludeList into a TreeSet in order to make searching the
		// list fast
		JsonArray excludeList = json.getJsonArray(Constants.EXCLUDE_LIST);
		this.excludeList = new TreeSet<String>();
		for (int i = 0; i < excludeList.size(); i++) {
			this.excludeList.add(excludeList.getString(i));
		}

		// Get the exportPath and create the FileExtractor
		fileExtractor = new IOHandler(exportPath, session);
		
		// Create EadBuilder
		try {
			InputStream eadInputStream = new FileInputStream(new File(pathToEadTemplate));
			eadBuilder = new EadBuilder(eadInputStream, xmlHandler);
			eadInputStream.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			response = JsonUtils.addErrorMessage(builder, "EAD template file not found!").build();
			return;
		} catch (JDOMException e) {
			builder.add("validationError", eadBuilder.getValidationErrorMessage());
			response = JsonUtils.addErrorMessage(builder, "EAD template file not valid according to ead3.xsd").build();
			return;
		} catch (IOException e) {
			e.printStackTrace();
			response = JsonUtils.addErrorMessage(builder, "An I/O error occured while handling the EAD template file!").build();
			return;
		}

		// Start traversing the CMIS tree
		JsonArray exportList = json.getJsonArray(Constants.EXPORT_LIST);
		for (int i = 0; i < exportList.size(); i++) {
			// We know (assume!) that the values in the exportList are strings

			// Get the CMIS object
			String objectId = exportList.getString(i);
			CmisObject cmisObject = session.getObject(objectId);

			// Get the CMIS types
			String cmisType = cmisObject.getType().getId();
			String semanticType = mappingParser.getSemanticTypeFromCmisType(cmisType);

			// Store the parent path for this current top-level node in the
			// CmisPathHandler
			Folder cmisFolder = (Folder) cmisObject;
			String folderPath;
			if (cmisFolder.isRootFolder()) {
				folderPath = cmisFolder.getPath();
			} else {
				folderPath = cmisFolder.getFolderParent().getPath();
			}
			cmisPathHandler = new CmisPathHandler(folderPath);

			// Get element for the current node in the exportList and add to
			// EAD
			Element c = metadataMapper.mapCElement(cmisObject, mappingParser.getHooksFromSemanticType(semanticType),
					mappingParser.getCElementFromSemanticType(semanticType));
			eadBuilder.addCElement(c, eadBuilder.getTopLevelElement());

			// This way of traversing the CMIS tree follows the example given in
			// the official documentation - see
			// http://chemistry.apache.org/java/developing/guide.html
			
			for (Tree<FileableCmisObject> tree : cmisFolder.getDescendants(-1)) {
				if (mappingParser.isLeaf(cmisType)) {
					handleLeafNodes(tree, c, cmisType, cmisFolder.getPath());
				} else {
					handleNode(tree, c);
				}
			}
		}

		// Validate EAD
		if (!xmlHandler.isXmlValid(eadBuilder.getEad(), "ead3.xsd")) {
			// TODO: Put schema location into constant
			response = JsonUtils.addErrorMessage(builder, "Generated EAD not valid: " + xmlHandler.getErrorMessage()).build();
			return;
		} else {
			// Copy EAD to correct location
			Path pathToValidEad = exportPath.resolve(fileExtractor.getMetadataFilePath().resolve("ead.xml"));
			XmlHandler.writeXml(eadBuilder.getEad(), pathToValidEad);
		}
		
		builder.add(Constants.SUCCESS, true);
		builder.add("path", exportPath.toString());
		builder.add(Constants.STATUS, ExtractionResource.DONE);
		response = builder.build();
		return;
	}
	
	public JsonObject getResponse() {
		return response;
	}

	private void handleNode(Tree<FileableCmisObject> tree, Element parent) {
		// System.out.println(tree.getItem().getId());

		CmisObject node = tree.getItem();
		String childObjectId = node.getId(); // E.g. a nodeRef in Alfresco...
		if (!excludeList.contains(childObjectId)) {

			// Get the CMIS object type id
			String cmisType = node.getType().getId();

			if (isObjectTypeInSematicStructure(cmisType)) {
				Element c = metadataMapper.mapCElement(node, mappingParser.getHooksFromCmisType(cmisType),
						mappingParser.getCElementFromCmisType(cmisType));
				eadBuilder.addCElement(c, parent);
				if (!mappingParser.isLeaf(cmisType)) {
					for (Tree<FileableCmisObject> children : tree.getChildren()) {
						handleNode(children, c);
					}
				} else {
					removeFirstDaoElement = true;
					// Flatten the folder/file structure below here and store
					// the metadata in <dao> elements

					// Get CMIS path for the semantic leaf (e.g. for the
					// "record")
					String cmisPath = ((Folder) node).getPath();

					// If no children -> remove dao element from c element
					List<Tree<FileableCmisObject>> children = tree.getChildren();
					if (children.isEmpty()) {
						metadataMapper.removeDaoElements(c);
					} else {
						for (Tree<FileableCmisObject> child : children) {
							handleLeafNodes(child, c, cmisType, cmisPath);
						}
					}
				}
			}
		}
	}

	private void handleLeafNodes(Tree<FileableCmisObject> tree, Element semanticLeaf, String semanticLeafCmisType,
			String parentPath) {
		
		CmisObject node = tree.getItem();
		String cmisObjectTypeId = node.getId();

		if (!excludeList.contains(cmisObjectTypeId)) {
			if (node.getBaseTypeId().equals(BaseTypeId.CMIS_DOCUMENT)) {
				String pathToParentFolder = cmisPathHandler.getRelativePath(parentPath);
				String pathToNode = pathToParentFolder + "/" + node.getName();
				Path filePath = Paths.get(pathToNode);
				
				// Create <dao> element
				
				Element dao = metadataMapper.mapDaoElement(node,
						mappingParser.getHooksFromCmisType(semanticLeafCmisType), semanticLeaf, fileExtractor.getDataFilePath().resolve(filePath));
				// MappingUtils.printElement(dao);

				// Insert <dao> element into <c> element
				if (removeFirstDaoElement) {
					// The first <dao> element is the one from the template -
					// must be removed
					metadataMapper.removeDaoElements(semanticLeaf);
					removeFirstDaoElement = false;
				}
				eadBuilder.addDaoElement(dao, semanticLeaf);
				
				// Extract the file contents
				
				try {
					fileExtractor.writeCmisDocument(filePath, cmisObjectTypeId);
				} catch (IOException e) {
					// TODO: create JSON
					e.printStackTrace();
				}
								

			} else if (node.getBaseTypeId().equals(BaseTypeId.CMIS_FOLDER)) {
				String pathToNode = ((Folder) node).getPath();
				for (Tree<FileableCmisObject> child : tree.getChildren()) {
					handleLeafNodes(child, semanticLeaf, semanticLeafCmisType, pathToNode);
				}
			} else {
				// Not handled...
			}
		}
	}

	private boolean isObjectTypeInSematicStructure(String objectTypeId) {
		Set<String> cmisObjectTypes = mappingParser.getObjectTypes().getAllCmisTypes();
		if (cmisObjectTypes.contains(objectTypeId)) {
			return true;
		}
		return false;
	}
}
