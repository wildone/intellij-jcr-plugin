package velir.intellij.cq5.jcr.model;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import velir.intellij.cq5.jcr.Connection;

import javax.jcr.*;
import java.util.*;

public class VNodeDefinition {
	public static final String JCR_AUTOCREATED = "jcr:autoCreated";
	public static final String JCR_CONTENT = "jcr:content";
	public static final String JCR_DEFAULTPRIMARYTYPE = "jcr:defaultPrimaryType";
	public static final String JCR_ISMIXIN = "jcr:isMixin";
	public static final String JCR_NAME = "jcr:name";
	public static final String JCR_NODETYPENAME = "jcr:nodeTypeName";
	public static final String JCR_ONPARENTVERSION = "jcr:onParentVersion";
	public static final String JCR_SUPERTYPES = "jcr:supertypes";
	public static final String NT_CHILDNODEDEFINITION = "nt:childNodeDefinition";
	public static final String NT_PROPERTYDEFINITION = "nt:propertyDefinition";
	public static final String CQ_CELLNAME = "cq:cellName";
	public static final String CQ_COMPONENT = "cq:Component";
	public static final String CQ_ISCONTAINER = "cq:isContainer";
	public static final String CQ_DIALOG = "cq:Dialog";
	public static final String CQ_PAGECONTENT = "cq:PageContent";
	public static final String CQ_TABPANEL = "cq:TabPanel";
	public static final String CQ_TEMPLATE = "cq:Template";
	public static final String CQ_WIDGET = "cq:Widget";
	public static final String CQ_WIDGETCOLLECTION = "cq:WidgetCollection";
	public static final String JCR_TITLE = "jcr:title";
	public static final String ALLOWED_PARENTS = "allowedParents";
	public static final String COMPONENT_GROUP = "componentGroup";

	private static final Logger log = com.intellij.openapi.diagnostic.Logger.getInstance(VNodeDefinition.class);

	private static Map<String,VNodeDefinition> allNodes;

	private Map<String,VPropertyDefinitionI> properties;
	private Set<String> supertypes;
	private Map<String,String> childSuggestions;
	private boolean canAddProperties;
	private boolean isMixin;
	private String name;

	public VNodeDefinition (Node node) throws RepositoryException {
		name = node.getProperty(JCR_NODETYPENAME).getString();

		// do properties
		properties = new HashMap<String, VPropertyDefinitionI>();
		childSuggestions = new HashMap<String, String>();
		NodeIterator nodeIterator = node.getNodes();
		while (nodeIterator.hasNext()) {
			Node definitionNode = nodeIterator.nextNode();
			String nodeType = definitionNode.getProperty(AbstractProperty.JCR_PRIMARYTYPE).getString();

			// do a property
			if (NT_PROPERTYDEFINITION.equals(nodeType)) {
				String propertyName = "*"; // default to wildcard name
				if (definitionNode.hasProperty(JCR_NAME)) {

					// only add non-autogenerated properties
					if (! definitionNode.getProperty(JCR_AUTOCREATED).getBoolean()) {
						propertyName = definitionNode.getProperty(JCR_NAME).getString();
						properties.put(propertyName, new VPropertyDefinition(definitionNode));
					}
				} else {
					// property with no name means this node can accept custom properties
					canAddProperties = true;
				}
			}

			// do a child suggestion
			if (NT_CHILDNODEDEFINITION.equals(nodeType)) {
				String childName = "*";
				// only do well-defined childnodedefinitions with the following 2 jcr properties
				if (definitionNode.hasProperty(JCR_NAME) && definitionNode.hasProperty(JCR_DEFAULTPRIMARYTYPE)) {
					childSuggestions.put(definitionNode.getProperty(JCR_NAME).getString(),
							definitionNode.getProperty(JCR_DEFAULTPRIMARYTYPE).getString());
				}
			}
		}

		// do supertypes
		supertypes = new HashSet<String>();
		if (node.hasProperty(JCR_SUPERTYPES)) {
			for (Value value : node.getProperty(JCR_SUPERTYPES).getValues()) {
				supertypes.add(value.getString());
			}
		}

		// set mixin status
		isMixin = node.hasProperty(JCR_ISMIXIN) && node.getProperty(JCR_ISMIXIN).getBoolean();
	}

	public Map<String, VProperty> getPropertiesMap (boolean includePrimaryType) {
		Map<String,VProperty> propertiesMap = new HashMap<String, VProperty>();
		for (Map.Entry<String, VPropertyDefinitionI> entry : properties.entrySet()) {
            //TODO: Replace with VProperty Factory
            VProperty newProp = new XMLProperty(name, entry.getValue().getDefaultValue() );
			propertiesMap.put(entry.getKey(), newProp);
		}
		if (includePrimaryType) {
            propertiesMap.put(AbstractProperty.JCR_PRIMARYTYPE, new XMLProperty(AbstractProperty.JCR_PRIMARYTYPE, name));
        }

		// also get supertype properties
		for (String supertype : supertypes) {
			VNodeDefinition vNodeDefinition = VNodeDefinition.getDefinition(supertype);
			if (vNodeDefinition != null) propertiesMap.putAll(vNodeDefinition.getPropertiesMap(false));
			else {
				log.error("Could not get definition for " + supertype );
			}
		}

		return propertiesMap;
	}

	public Map<String, String> getChildSuggestions() {
		Map<String, String> suggestions = new HashMap<String, String>();
		// get supertype child suggestions
		for (String supertype : supertypes) {
			VNodeDefinition vNodeDefinition = VNodeDefinition.getDefinition(supertype);
			suggestions.putAll(vNodeDefinition.getChildSuggestions());
		}

		// now put in this node's suggestions
		suggestions.putAll(childSuggestions);

		return suggestions;
	}

	public static void buildDefinitions (Project project) {
		log.info("started building node definitions");
		Session session = null;
		String nodeName = "";
		try {
			allNodes = new HashMap<String, VNodeDefinition>();
			Connection connection = Connection.getInstance(project);
			session = connection.getSession();
			Node rootNode = session.getNode("/jcr:system/jcr:nodeTypes");
			NodeIterator nodeIterator = rootNode.getNodes();
			while (nodeIterator.hasNext()) {
				Node node = nodeIterator.nextNode();
				nodeName = node.getName();
				VNodeDefinition vNodeDefinition = new VNodeDefinition(node);
				customizeDefinition(vNodeDefinition); //possibly customize this definition
				allNodes.put(nodeName, vNodeDefinition);
			}
			log.info("finished building nodes");
		} catch (RepositoryException re) {
			log.warn("Could not build node definitions, died at " + nodeName, re);
		} finally {
			if (session != null) session.logout();
		}
	}

	// extend the lacking JCR definitions
	public static void customizeDefinition (VNodeDefinition vNodeDefinition) {
		String name = vNodeDefinition.name;

		if (CQ_COMPONENT.equals(name)) {
			vNodeDefinition.properties.put(ALLOWED_PARENTS, new VPropertyDefinitionI() {
				public Object getDefaultValue() {
					return "*/parsys";
				}
			});
			vNodeDefinition.properties.put(COMPONENT_GROUP, new VPropertyDefinitionI() {
				public Object getDefaultValue() {
					return "General";
				}
			});
			vNodeDefinition.properties.remove(CQ_CELLNAME); // causes problems if blank (and maybe in general)
			vNodeDefinition.childSuggestions.put("dialog", CQ_DIALOG);
			vNodeDefinition.childSuggestions.put("design_dialog", CQ_DIALOG);
		}

		else if (CQ_DIALOG.equals(name)) {
			vNodeDefinition.properties.put("xtype", new VPropertyDefinitionI() {
				public Object getDefaultValue() {
					return "dialog";
				}
			});
			vNodeDefinition.childSuggestions.put("items", CQ_TABPANEL);
		}

		else if (CQ_TEMPLATE.equals(name)) {
			vNodeDefinition.properties.put("allowedPaths", new VPropertyDefinitionI() {
				public Object getDefaultValue() {
					return "/content(/.*)?";
				}
			});
			vNodeDefinition.childSuggestions.put(JCR_CONTENT, CQ_PAGECONTENT);
		}

		else if (CQ_WIDGETCOLLECTION.equals(name)) {
			vNodeDefinition.childSuggestions.put("widget", CQ_WIDGET);
		}
	}

	public static boolean hasDefinitions () {
		return ! allNodes.isEmpty();
	}

	// only include non-mixin types
	public static String[] getNodeTypeNames () {
		// filter mixins out
		Set<String> keySet = allNodes.keySet();
		Set<String> copySet = new HashSet<String>();
		for (String s : keySet) copySet.add(s); // do I really need to do this, java?
		for (String s : keySet) {
			if (getDefinition(s).isMixin) copySet.remove(s);
		}

		String[] options = new String[copySet.size()];
		options = copySet.toArray(options);
		Arrays.sort(options);
		return options;
	}

	public static VNodeDefinition getDefinition (String name) {
		return allNodes.get(name);
	}


}
