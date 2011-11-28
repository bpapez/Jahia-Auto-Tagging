package org.jahia.modules.autotagging.rules;

import static org.apache.stanbol.enhancer.servicesapi.rdf.OntologicalClasses.DBPEDIA_ORGANISATION;
import static org.apache.stanbol.enhancer.servicesapi.rdf.OntologicalClasses.DBPEDIA_PERSON;
import static org.apache.stanbol.enhancer.servicesapi.rdf.OntologicalClasses.DBPEDIA_PLACE;
import static org.apache.stanbol.enhancer.servicesapi.rdf.OntologicalClasses.SKOS_CONCEPT;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.DC_RELATION;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.DC_TYPE;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.ENHANCER_CONFIDENCE;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.ENHANCER_ENTITY_LABEL;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.ENHANCER_ENTITY_REFERENCE;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.ENHANCER_SELECTED_TEXT;
import static org.apache.stanbol.enhancer.servicesapi.rdf.TechnicalClasses.ENHANCER_ENTITYANNOTATION;
import static org.apache.stanbol.enhancer.servicesapi.rdf.TechnicalClasses.ENHANCER_TEXTANNOTATION;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;

import org.apache.clerezza.rdf.core.Graph;
import org.apache.clerezza.rdf.core.Language;
import org.apache.clerezza.rdf.core.Literal;
import org.apache.clerezza.rdf.core.LiteralFactory;
import org.apache.clerezza.rdf.core.NonLiteral;
import org.apache.clerezza.rdf.core.PlainLiteral;
import org.apache.clerezza.rdf.core.Resource;
import org.apache.clerezza.rdf.core.Triple;
import org.apache.clerezza.rdf.core.TripleCollection;
import org.apache.clerezza.rdf.core.UriRef;
import org.apache.clerezza.rdf.core.serializedform.Parser;
import org.apache.clerezza.rdf.core.sparql.ParseException;
import org.apache.clerezza.rdf.ontologies.RDF;
import org.apache.commons.lang.StringUtils;
import org.apache.stanbol.enhancer.servicesapi.helper.EnhancementEngineHelper;
import org.drools.spi.KnowledgeHelper;
import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.SelectorType;
import org.jahia.services.content.rules.AddedNodeFact;
import org.jahia.services.notification.HttpClientService;
import org.jahia.services.tags.TaggingService;
import org.slf4j.Logger;

public class ContentEnhancementService {

    private static Logger logger = org.slf4j.LoggerFactory.getLogger(ContentEnhancementService.class);

    // TODO make this configurable trough a property
    public static final UriRef SUMMARY = new UriRef("http://www.w3.org/2000/01/rdf-schema#comment");

    // TODO make this configurable trough a property
    public static final UriRef THUMBNAIL = new UriRef("http://dbpedia.org/ontology/thumbnail");
    public static final UriRef DEPICTION = new UriRef("http://xmlns.com/foaf/0.1/depiction");

    public final Map<UriRef, String> defaultThumbnails = new HashMap<UriRef, String>();

    private TaggingService taggingService;
    private HttpClientService httpClientService;
    private String enhancementEngineUrl;

    enum EAProps {
        label, entity, confidence
    }

    public TaggingService getTaggingService() {
        return taggingService;
    }

    public void setTaggingService(TaggingService taggingService) {
        this.taggingService = taggingService;
    }

    /**
     * Obtains all text properties of the node and consults a semantic enhancement engine to obtain extracted tags.
     * 
     * @param node
     *            the node to add tags
     * @param drools
     *            the rule engine helper class
     * @throws RepositoryException
     *             in case of an error
     */
    public void performAutoTagging(final AddedNodeFact nodeFact, KnowledgeHelper drools) throws RepositoryException, ParseException {
        if (logger.isDebugEnabled()) {
            logger.debug("Automatically adding tags for node " + nodeFact.getPath());
        }

        final JCRNodeWrapper node = nodeFact.getNode();
        JCRSiteNode site = node.getResolveSite();
        if (site != null) {
            final String siteKey = site.getSiteKey();
            PropertyIterator it = node.getProperties();
            StringBuilder data = new StringBuilder();
            while (it.hasNext()) {
                Property property = (Property) it.next();
                if (property.getType() == PropertyType.STRING) {
                    ExtendedPropertyDefinition propDef = (ExtendedPropertyDefinition) property.getDefinition();
                    if (propDef.getSelector() == SelectorType.RICHTEXT) {
                        if (data.length() != 0) {
                            data.append('\n');
                        }
                        data.append(property.getString());
                    }
                }
            }
            if (data.length() != 0) {
                Map<String, String> parameters = new HashMap<String, String>();
                Map<String, String> headers = new HashMap<String, String>();
                parameters.put("data", data.toString());
                headers.put("Accept", "application/rdf+xml");
                headers.put("Content-type", "text/plain");
                String content = getHttpClientService().executePost(getEnhancementEngineUrl(), parameters, headers);
                Map<UriRef, Map<String, EntityExtractionSummary>> extractionsByTypeMap = Collections.emptyMap();
                if (!content.isEmpty()) {
                    try {
                        final Parser parser = Parser.getInstance();
                        Graph deserializedGraph = parser.parse(new ByteArrayInputStream(content.getBytes("UTF-8")), "application/rdf+xml");

                        extractionsByTypeMap = initOccurrences(deserializedGraph);
                        node.setProperty("j:performAutoTagging", false);
                    } catch (Exception e) {
                        logger.error("Error while parsing enhancement result", e);
                    }
                }

                Set<String> tags = new HashSet<String>();
                for (Map<String, EntityExtractionSummary> extractions : extractionsByTypeMap.values()) {
                    for (EntityExtractionSummary entity : extractions.values()) {

                        tags.add(entity.getName());
                    }
                }
                if (!tags.isEmpty()) {
                    if (!node.isNodeType(Constants.JAHIAMIX_TAGGED)) {
                        node.addMixin(Constants.JAHIAMIX_TAGGED);
                    }
                    taggingService.tag(node.getPath(), StringUtils.join(tags, ","), siteKey, true, node.getSession());
                }

            }
        }
    }

    private Map<UriRef, Map<String, EntityExtractionSummary>> initOccurrences(Graph graph) {
        Map<UriRef, Map<String, EntityExtractionSummary>> extractionsByTypeMap = new HashMap<UriRef, Map<String, EntityExtractionSummary>>();
        LiteralFactory lf = LiteralFactory.getInstance();
        Map<UriRef, Collection<NonLiteral>> suggestionMap = new HashMap<UriRef, Collection<NonLiteral>>();
        // 1) get Entity Annotations
        Map<NonLiteral, Map<EAProps, Object>> entitySuggestionMap = new HashMap<NonLiteral, Map<EAProps, Object>>();
        Iterator<Triple> entityAnnotations = graph.filter(null, RDF.type, ENHANCER_ENTITYANNOTATION);
        while (entityAnnotations.hasNext()) {
            NonLiteral entityAnnotation = entityAnnotations.next().getSubject();
            // to avoid multiple lookups (e.g. if one entityAnnotation links to+
            // several TextAnnotations) we cache the data in an intermediate Map
            Map<EAProps, Object> eaData = new EnumMap<EAProps, Object>(EAProps.class);
            eaData.put(EAProps.entity, getReference(graph, entityAnnotation, ENHANCER_ENTITY_REFERENCE));
            eaData.put(EAProps.label, getString(graph, entityAnnotation, ENHANCER_ENTITY_LABEL));
            eaData.put(EAProps.confidence, EnhancementEngineHelper.get(graph, entityAnnotation, ENHANCER_CONFIDENCE, Double.class, lf));
            entitySuggestionMap.put(entityAnnotation, eaData);
            Iterator<UriRef> textAnnotations = getReferences(graph, entityAnnotation, DC_RELATION);
            while (textAnnotations.hasNext()) {
                UriRef textAnnotation = textAnnotations.next();
                Collection<NonLiteral> suggestions = suggestionMap.get(textAnnotation);
                if (suggestions == null) {
                    suggestions = new ArrayList<NonLiteral>();
                    suggestionMap.put(textAnnotation, suggestions);
                }
                suggestions.add(entityAnnotation);
            }
        }
        // 2) get the TextAnnotations
        Iterator<Triple> textAnnotations = graph.filter(null, RDF.type, ENHANCER_TEXTANNOTATION);
        while (textAnnotations.hasNext()) {
            NonLiteral textAnnotation = textAnnotations.next().getSubject();
            if (graph.filter(textAnnotation, DC_RELATION, null).hasNext()) {
                // this is not the most specific occurrence of this name: skip
                continue;
            }
            String text = getString(graph, textAnnotation, ENHANCER_SELECTED_TEXT);
            if (text == null) {
                // ignore text annotations without text
                continue;
            }
            Iterator<UriRef> types = getReferences(graph, textAnnotation, DC_TYPE);
            if (!types.hasNext()) { // create an iterator over null in case no types are present
                types = Collections.singleton((UriRef) null).iterator();
            }
            while (types.hasNext()) {
                UriRef type = types.next();
                Map<String, EntityExtractionSummary> occurrenceMap = extractionsByTypeMap.get(type);
                if (occurrenceMap == null) {
                    occurrenceMap = new TreeMap<String, EntityExtractionSummary>(String.CASE_INSENSITIVE_ORDER);
                    extractionsByTypeMap.put(type, occurrenceMap);
                }
                EntityExtractionSummary entity = occurrenceMap.get(text);
                if (entity == null) {
                    entity = new EntityExtractionSummary(text, type, defaultThumbnails);
                    occurrenceMap.put(text, entity);
                }
                Collection<NonLiteral> suggestions = suggestionMap.get(textAnnotation);
                if (suggestions != null) {
                    for (NonLiteral entityAnnotation : suggestions) {
                        Map<EAProps, Object> eaData = entitySuggestionMap.get(entityAnnotation);
                        entity.addSuggestion((UriRef) eaData.get(EAProps.entity), (String) eaData.get(EAProps.label),
                                (Double) eaData.get(EAProps.confidence), graph);
                    }
                }
            }
        }
        return extractionsByTypeMap;
    }

    public Collection<EntityExtractionSummary> getOccurrences(UriRef type,
            Map<UriRef, Map<String, EntityExtractionSummary>> extractionsByTypeMap) {
        Map<String, EntityExtractionSummary> typeMap = extractionsByTypeMap.get(type);
        Collection<EntityExtractionSummary> typeOccurrences;
        if (typeMap != null) {
            typeOccurrences = typeMap.values();
        } else {
            typeOccurrences = Collections.emptyList();
        }
        return typeOccurrences;
    }

    public Collection<EntityExtractionSummary> getPersonOccurrences(Map<UriRef, Map<String, EntityExtractionSummary>> extractionsByTypeMap)
            throws ParseException {
        return getOccurrences(DBPEDIA_PERSON, extractionsByTypeMap);
    }

    public Collection<EntityExtractionSummary> getOtherOccurrences(Map<UriRef, Map<String, EntityExtractionSummary>> extractionsByTypeMap)
            throws ParseException {
        return getOccurrences(null, extractionsByTypeMap);
    }

    public Collection<EntityExtractionSummary> getOrganizationOccurrences(
            Map<UriRef, Map<String, EntityExtractionSummary>> extractionsByTypeMap) throws ParseException {
        return getOccurrences(DBPEDIA_ORGANISATION, extractionsByTypeMap);
    }

    public Collection<EntityExtractionSummary> getPlaceOccurrences(Map<UriRef, Map<String, EntityExtractionSummary>> extractionsByTypeMap)
            throws ParseException {
        return getOccurrences(DBPEDIA_PLACE, extractionsByTypeMap);
    }

    public Collection<EntityExtractionSummary> getConceptOccurrences(Map<UriRef, Map<String, EntityExtractionSummary>> extractionsByTypeMap)
            throws ParseException {
        return getOccurrences(SKOS_CONCEPT, extractionsByTypeMap);
    }

    public HttpClientService getHttpClientService() {
        return httpClientService;
    }

    public void setHttpClientService(HttpClientService httpClientService) {
        this.httpClientService = httpClientService;
    }

    public String getEnhancementEngineUrl() {
        return enhancementEngineUrl;
    }

    public void setEnhancementEngineUrl(String enhancementEngineUrl) {
        this.enhancementEngineUrl = enhancementEngineUrl;
    }

    public static class EntityExtractionSummary implements Comparable<EntityExtractionSummary> {

        protected final String name;

        protected final UriRef type;

        protected List<EntitySuggestion> suggestions = new ArrayList<EntitySuggestion>();

        protected List<String> mentions = new ArrayList<String>();

        public final Map<UriRef, String> defaultThumbnails;

        public EntityExtractionSummary(String name, UriRef type, Map<UriRef, String> defaultThumbnails) {
            this.name = name;
            this.type = type;
            mentions.add(name);
            this.defaultThumbnails = defaultThumbnails;
        }

        public void addSuggestion(UriRef uri, String label, Double confidence, TripleCollection properties) {
            EntitySuggestion suggestion = new EntitySuggestion(uri, type, label, confidence, properties, defaultThumbnails);
            if (!suggestions.contains(suggestion)) {
                suggestions.add(suggestion);
                Collections.sort(suggestions);
            }
        }

        public String getName() {
            EntitySuggestion bestGuess = getBestGuess();
            if (bestGuess != null) {
                return bestGuess.getLabel();
            }
            return name;
        }

        public String getUri() {
            EntitySuggestion bestGuess = getBestGuess();
            if (bestGuess != null) {
                return bestGuess.getUri();
            }
            return null;
        }

        public String getSummary() {
            if (suggestions.isEmpty()) {
                return "";
            }
            return suggestions.get(0).getSummary();
        }

        public String getThumbnailSrc() {
            if (suggestions.isEmpty()) {
                return defaultThumbnails.get(type);
            }
            return suggestions.get(0).getThumbnailSrc();
        }

        public String getMissingThumbnailSrc() {
            return defaultThumbnails.get(type);
        }

        public EntitySuggestion getBestGuess() {
            if (suggestions.isEmpty()) {
                return null;
            }
            return suggestions.get(0);
        }

        public List<EntitySuggestion> getSuggestions() {
            return suggestions;
        }

        public List<String> getMentions() {
            return mentions;
        }

        public int compareTo(EntityExtractionSummary o) {
            return getName().compareTo(o.getName());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            EntityExtractionSummary that = (EntityExtractionSummary) o;

            return !(name != null ? !name.equals(that.name) : that.name != null);
        }

        @Override
        public int hashCode() {
            return name != null ? name.hashCode() : 0;
        }
    }

    public static class EntitySuggestion implements Comparable<EntitySuggestion> {

        protected final UriRef uri;

        protected final UriRef type;

        protected final String label;

        protected final Double confidence;

        protected TripleCollection entityProperties;

        protected final Map<UriRef, String> defaultThumbnails;

        public EntitySuggestion(UriRef uri, UriRef type, String label, Double confidence, TripleCollection entityProperties,
                Map<UriRef, String> defaultThumbnails) {
            this.uri = uri;
            this.label = label;
            this.type = type;
            this.confidence = confidence != null ? confidence : 0.0;
            this.entityProperties = entityProperties;
            this.defaultThumbnails = defaultThumbnails;
        }

        public int compareTo(EntitySuggestion o) {
            // order suggestions by decreasing confidence
            return -confidence.compareTo(o.confidence);
        }

        public String getUri() {
            return uri.getUnicodeString();
        }

        public Double getConfidence() {
            return confidence;
        }

        public String getLabel() {
            return label;
        }

        public String getThumbnailSrc() {
            Iterator<Triple> thumbnails = entityProperties.filter(uri, THUMBNAIL, null);
            while (thumbnails.hasNext()) {
                Resource object = thumbnails.next().getObject();
                if (object instanceof UriRef) {
                    return ((UriRef) object).getUnicodeString();
                }
            }
            // if no dbpedia ontology thumbnail was found. try the same with foaf:depiction
            thumbnails = entityProperties.filter(uri, DEPICTION, null);
            while (thumbnails.hasNext()) {
                Resource object = thumbnails.next().getObject();
                if (object instanceof UriRef) {
                    return ((UriRef) object).getUnicodeString();
                }
            }
            return defaultThumbnails.get(type);
        }

        public String getMissingThumbnailSrc() {
            return defaultThumbnails.get(type);
        }

        public String getSummary() {
            Iterator<Triple> abstracts = entityProperties.filter(uri, SUMMARY, null);
            while (abstracts.hasNext()) {
                Resource object = abstracts.next().getObject();
                if (object instanceof PlainLiteral) {
                    PlainLiteral abstract_ = (PlainLiteral) object;
                    if (new Language("en").equals(abstract_.getLanguage())) {
                        return abstract_.getLexicalForm();
                    }
                }
            }
            return "";
        }

        // consider entities with same URI as equal even if we have alternate
        // label values
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((uri == null) ? 0 : uri.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            EntitySuggestion other = (EntitySuggestion) obj;
            if (uri == null) {
                if (other.uri != null)
                    return false;
            } else if (!uri.equals(other.uri))
                return false;
            return true;
        }

    }

    /**
     * Getter for the first String literal value the property for a resource
     * 
     * @param graph
     *            the graph used to query for the property value
     * @param resource
     *            the resource
     * @param property
     *            the property
     * @return the value
     */
    public static String getString(TripleCollection graph, NonLiteral resource, UriRef property) {
        Iterator<Triple> results = graph.filter(resource, property, null);
        if (results.hasNext()) {
            while (results.hasNext()) {
                Triple result = results.next();
                if (result.getObject() instanceof Literal) {
                    return ((Literal) result.getObject()).getLexicalForm();
                } else {
                    logger.debug("Triple {} does not have a literal as object! -> ignore", result);
                }
            }
            logger.info("No Literal value for {} and property {} -> return null", resource, property);
            return null;
        } else {
            logger.debug("No Triple found for " + resource + " and property " + property + "! -> return null");
            return null;
        }
    }

    /**
     * Getter for the first value of the data type property for a resource
     * 
     * @param graph
     *            the graph used to query for the property value
     * @param resource
     *            the resource
     * @param property
     *            the property
     * @return the value
     */
    public static UriRef getReference(Graph graph, NonLiteral resource, UriRef property) {
        Iterator<Triple> results = graph.filter(resource, property, null);
        if (results.hasNext()) {
            while (results.hasNext()) {
                Triple result = results.next();
                if (result.getObject() instanceof UriRef) {
                    return (UriRef) result.getObject();
                } else {
                    logger.debug("Triple " + result + " does not have a UriRef as object! -> ignore");
                }
            }
            logger.info("No UriRef value for {} and property {} -> return null", resource, property);
            return null;
        } else {
            logger.debug("No Triple found for {} and property {}! -> return null", resource, property);
            return null;
        }
    }

    /**
     * Getter for the values of the data type property for a resource.
     * 
     * @param graph
     *            the graph used to query for the property value
     * @param resource
     *            the resource
     * @param property
     *            the property
     * @return The iterator over all the values (
     */
    public static Iterator<UriRef> getReferences(Graph graph, NonLiteral resource, UriRef property) {
        final Iterator<Triple> results = graph.filter(resource, property, null);
        return new Iterator<UriRef>() {
            // TODO: dose not check if the object of the triple is of type UriRef

            public boolean hasNext() {
                return results.hasNext();
            }

            public UriRef next() {
                return (UriRef) results.next().getObject();
            }

            public void remove() {
                results.remove();
            }
        };
    }
}
