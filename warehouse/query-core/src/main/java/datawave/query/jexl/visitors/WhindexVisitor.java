package datawave.query.jexl.visitors;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import datawave.query.config.ShardQueryConfiguration;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.JexlNodeFactory;
import datawave.query.jexl.LiteralRange;
import datawave.query.jexl.functions.FunctionJexlNodeVisitor;
import datawave.query.jexl.functions.GeoWaveFunctionsDescriptor;
import datawave.query.jexl.functions.JexlFunctionArgumentDescriptorFactory;
import datawave.query.jexl.functions.arguments.JexlArgumentDescriptor;
import datawave.query.jexl.nodes.BoundedRange;
import datawave.query.util.MetadataHelper;
import datawave.util.time.DateHelper;
import datawave.webservice.common.logging.ThreadConfigurableLogger;
import org.apache.commons.jexl2.parser.ASTAndNode;
import org.apache.commons.jexl2.parser.ASTDelayedPredicate;
import org.apache.commons.jexl2.parser.ASTEQNode;
import org.apache.commons.jexl2.parser.ASTERNode;
import org.apache.commons.jexl2.parser.ASTFunctionNode;
import org.apache.commons.jexl2.parser.ASTGENode;
import org.apache.commons.jexl2.parser.ASTGTNode;
import org.apache.commons.jexl2.parser.ASTIdentifier;
import org.apache.commons.jexl2.parser.ASTLENode;
import org.apache.commons.jexl2.parser.ASTLTNode;
import org.apache.commons.jexl2.parser.ASTNENode;
import org.apache.commons.jexl2.parser.ASTNRNode;
import org.apache.commons.jexl2.parser.ASTNotNode;
import org.apache.commons.jexl2.parser.ASTOrNode;
import org.apache.commons.jexl2.parser.ASTReference;
import org.apache.commons.jexl2.parser.ASTReferenceExpression;
import org.apache.commons.jexl2.parser.JexlNode;
import org.apache.commons.jexl2.parser.JexlNodes;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class WhindexVisitor extends RebuildingVisitor {
    private static final Logger log = ThreadConfigurableLogger.getLogger(WhindexVisitor.class);
    
    private final Set<String> mappingFields;
    private final Map<String,Map<String,String>> valueSpecificFieldMappings;
    private final MetadataHelper metadataHelper;
    
    private final HashMap<JexlNode,WhindexTerm> jexlNodeToWhindexMap = new HashMap<>();
    
    private static class ExpandData {
        public boolean foundWhindex = false;
        public Multimap<String,JexlNode> andedNodes = LinkedHashMultimap.create();
        public Multimap<String,JexlNode> usedAndedNodes = LinkedHashMultimap.create();
    }
    
    private WhindexVisitor(ShardQueryConfiguration config, Date beginDate, MetadataHelper metadataHelper) {
        this(config.getWhindexMappingFields(), config.getWhindexFieldMappings(), beginDate, metadataHelper);
    }
    
    private WhindexVisitor(Set<String> mappingFields, Map<String,Map<String,String>> fieldMappings, Date beginDate, MetadataHelper metadataHelper) {
        this.mappingFields = mappingFields;
        this.metadataHelper = metadataHelper;
        this.valueSpecificFieldMappings = pruneFieldMappings(fieldMappings, beginDate);
    }
    
    /**
     * Expand all nodes which have multiple dataTypes for the field.
     *
     * @param config
     *            Configuration parameters relevant to our query
     * @param script
     *            The jexl node representing the query
     * @return An expanded version of the passed-in script containing whindex nodes
     */
    public static <T extends JexlNode> T apply(T script, ShardQueryConfiguration config, Date beginDate, MetadataHelper metadataHelper) {
        WhindexVisitor visitor = new WhindexVisitor(config, beginDate, metadataHelper);
        
        return apply(script, visitor);
    }
    
    /**
     * Expand all nodes which have multiple dataTypes for the field.
     *
     * @param mappingFields
     *            The value-specific fields required to create a Whindex
     * @param fieldMappings
     *            The value-specific field mappings which can be used to create a Whindex
     * @param script
     *            The jexl node representing the query
     * @return An expanded version of the passed-in script containing whindex nodes
     */
    public static <T extends JexlNode> T apply(T script, Set<String> mappingFields, Map<String,Map<String,String>> fieldMappings, Date beginDate,
                    MetadataHelper metadataHelper) {
        WhindexVisitor visitor = new WhindexVisitor(mappingFields, fieldMappings, beginDate, metadataHelper);
        
        return apply(script, visitor);
    }
    
    @SuppressWarnings("unchecked")
    private static <T extends JexlNode> T apply(T script, WhindexVisitor visitor) {
        // if there are any geowave functions with multiple fields, split them up into individual functions
        script = SplitGeoWaveFunctionVisitor.apply(script);
        
        // need to flatten the tree so i get all and nodes at the same level
        script = TreeFlatteningRebuildingVisitor.flatten(script);
        
        return (T) script.jjtAccept(visitor, new ExpandData());
    }
    
    private Map<String,Map<String,String>> pruneFieldMappings(Map<String,Map<String,String>> valueSpecificFieldMappings, Date beginDate) {
        Map<String,Map<String,String>> prunedFieldMappings = new HashMap<>();
        if (beginDate != null) {
            for (String fieldValue : valueSpecificFieldMappings.keySet()) {
                Map<String,String> mapping = valueSpecificFieldMappings.get(fieldValue);
                for (String origField : mapping.keySet()) {
                    String newField = mapping.get(origField);
                    // if the new field predates the query range, we can use this mapping
                    if (fieldExistsForDay(newField, beginDate)) {
                        Map<String,String> newMapping = prunedFieldMappings.computeIfAbsent(fieldValue, k -> new HashMap<>());
                        newMapping.put(origField, newField);
                    }
                }
            }
        }
        return prunedFieldMappings;
    }
    
    private boolean fieldExistsForDay(String field, Date date) {
        return metadataHelper.getCountsByFieldInDay(field, DateHelper.format(date)) > 0;
    }
    
    /**
     * Descends into each of the child nodes, and rebuilds the 'or' node with both the unmodified and modified nodes. Ancestor anded nodes are passed down to
     * each child, and the foundWhindex flag is passed up from the children.
     *
     * @param node
     *            An 'or' node from the original script
     * @param data
     *            ExpandData, containing ancestor anded nodes, used anded nodes, and a flag indicating whether whindexes were found
     * @return An expanded version of the 'or' node containing whindex nodes, if found, or the original in node, if not found
     */
    @Override
    public Object visit(ASTOrNode node, Object data) {
        ExpandData parentData = (ExpandData) data;
        
        // if we only have one child, just pass through
        // this shouldn't ever really happen, but it could
        if (node.jjtGetNumChildren() == 1)
            return super.visit(node, data);
        
        // iterate over the children and attempt to create whindexes
        List<JexlNode> unmodifiedNodes = new ArrayList<>();
        List<JexlNode> modifiedNodes = new ArrayList<>();
        List<String> modifiedNodesAsString = new ArrayList<>();
        for (JexlNode child : JexlNodes.children(node)) {
            ExpandData eData = new ExpandData();
            
            // add the anded leaf nodes from our ancestors
            eData.andedNodes.putAll(parentData.andedNodes);
            
            JexlNode processedNode = (JexlNode) child.jjtAccept(this, eData);
            String processedNodeAsString = JexlStringBuildingVisitor.buildQuery(processedNode);
            
            // if whindexes were made, save the processed node,
            // and keep track of the used anded nodes
            if (eData.foundWhindex) {
                // it is possible that multiple whindex combinations map to
                // the same final node. use this check to eliminate dupes
                if (!modifiedNodesAsString.contains(processedNodeAsString)) {
                    modifiedNodes.add(processedNode);
                    modifiedNodesAsString.add(processedNodeAsString);
                    parentData.foundWhindex = true;
                    parentData.usedAndedNodes.putAll(eData.usedAndedNodes);
                }
            } else
                unmodifiedNodes.add(child);
        }
        
        List<JexlNode> processedNodes = new ArrayList<>();
        processedNodes.addAll(unmodifiedNodes);
        processedNodes.addAll(modifiedNodes);
        
        // if we found a whindex, rebuild the or node,
        // otherwise, return the original or node
        if (parentData.foundWhindex) {
            return createUnwrappedOrNode(processedNodes);
        } else
            return copy(node);
    }
    
    private boolean isFieldValueMatchOrNode(JexlNode node) {
        JexlNode dereferenced = JexlASTHelper.dereference(node);
        return dereferenced instanceof ASTOrNode && containsFieldValueMatch((ASTOrNode) dereferenced);
    }
    
    private boolean containsFieldValueMatch(ASTOrNode orNode) {
        for (JexlNode child : JexlNodes.children(orNode)) {
            if (child instanceof ASTEQNode && isFieldValueTerm((ASTEQNode) child)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Rebuilds the current 'and' node, and attempts to create the best whindexes from the leaf and ancestor anded nodes available. First, we descend into the
     * non-leaf nodes, and keep track of which leaf and anded nodes are used. We then attempt to create whindexes from the remaining leaf and anded nodes.
     * Finally, any leftover, unused leaf nodes are anded at this level, while the used leaf nodes are passed down to the descendants and anded where
     * appropriate.
     *
     * @param node
     *            An 'and' node from the original script
     * @param data
     *            ExpandData, containing ancestor anded nodes, used anded nodes, and a flag indicating whether whindexes were found
     * @return An expanded version of the 'and' node containing whindex nodes, if found, or the original in node, if not found
     */
    @Override
    public Object visit(ASTAndNode node, Object data) {
        ExpandData parentData = (ExpandData) data;
        
        // only process delayed and bounded range predicates
        if (QueryPropertyMarkerVisitor.instanceOfAnyExcept(node, Lists.newArrayList(ASTDelayedPredicate.class, BoundedRange.class))) {
            return copy(node);
        }
        
        // if we only have one child, just pass through
        // this shouldn't ever really happen, but it could
        if (node.jjtGetNumChildren() == 1)
            return super.visit(node, data);
        
        // first, find all leaf nodes
        // note: an 'and' node defining a range over a single term is considered a leaf node for our purposes
        List<JexlNode> nonLeafNodes = new ArrayList<>();
        Multimap<String,JexlNode> leafNodes = getLeafNodes(node, nonLeafNodes);
        
        // if any of the non-leaf nodes is an OR with a marker node, distribute all of the sibling nodes into those OR nodes
        List<JexlNode> orNodesWithMarkers = nonLeafNodes.stream().filter(this::isFieldValueMatchOrNode).collect(Collectors.toList());
        if (!orNodesWithMarkers.isEmpty()) {
            // distribute the anded nodes into the or nodes
            JexlNode distributedNode = distributeAndedNodes(node, orNodesWithMarkers);
            
            // create and visit the distributed node
            JexlNode rebuiltNode = (JexlNode) distributedNode.jjtAccept(this, parentData);
            
            // only return the distributed version of this node if whindexes were found
            return (parentData.foundWhindex) ? rebuiltNode : node;
        } else {
            // if this is a 'leaf' range node, check to see if a whindex can be made
            if (leafNodes.size() == 1 && leafNodes.containsValue(node)) {
                // attempt to build a whindex
                return visitLeafNode(node, parentData);
            }
            // otherwise, process the 'and' node as usual
            else {
                
                Multimap<String,JexlNode> usedLeafNodes = LinkedHashMultimap.create();
                
                // process the non-leaf nodes first
                List<JexlNode> processedNonLeafNodes = processNonLeafNodes(parentData, nonLeafNodes, leafNodes, usedLeafNodes);
                
                // remove the used nodes from the leaf and anded nodes
                parentData.andedNodes.values().removeAll(parentData.usedAndedNodes.values());
                
                // next, process the remaining leaf nodes
                List<WhindexTerm> whindexTerms = new ArrayList<>();
                List<JexlNode> processedLeafNodes = processUnusedLeafNodes(parentData, leafNodes, usedLeafNodes, whindexTerms);
                
                // again, remove the used nodes from the leaf and anded nodes
                leafNodes.values().removeAll(usedLeafNodes.values());
                parentData.andedNodes.values().removeAll(parentData.usedAndedNodes.values());
                
                // rebuild the node if whindexes are found
                if (parentData.foundWhindex) {
                    List<JexlNode> processedNodes = new ArrayList<>();
                    processedNodes.addAll(processedLeafNodes);
                    processedNodes.addAll(processedNonLeafNodes);
                    
                    // rebuild the node
                    JexlNode rebuiltNode = createUnwrappedAndNode(processedNodes);
                    
                    // distribute the used nodes into the rebuilt node
                    if (!usedLeafNodes.values().isEmpty()) {
                        // first we need to trim the used nodes to eliminate any wrapping nodes
                        // i.e. reference, reference expression, or single child and/or nodes
                        // remove any leaf nodes that are still present in whindex form
                        List<JexlNode> leafNodesToDistribute = usedLeafNodes.values().stream().map(this::getLeafNode).collect(Collectors.toList());
                        
                        // remove all of the nodes that were turned into whindex nodes
                        leafNodesToDistribute.removeAll(whindexTerms.stream().map(x -> JexlASTHelper.dereference(x.getMappableNode()))
                                        .collect(Collectors.toList()));
                        
                        rebuiltNode = DistributeAndedNodes.distributeAndedNode(rebuiltNode, leafNodesToDistribute, jexlNodeToWhindexMap);
                    }
                    
                    return rebuiltNode;
                }
                
                return copy(node);
            }
        }
    }
    
    private JexlNode distributeAndedNodes(ASTAndNode node, List<JexlNode> orNodesWithMarkers) {
        // distribute the non-marker nodes into the marker nodes
        List<JexlNode> nodesToDistribute = new ArrayList<>();
        for (JexlNode child : JexlNodes.children(node)) {
            if (!orNodesWithMarkers.contains(child)) {
                nodesToDistribute.add(child);
            }
        }
        
        List<JexlNode> newOrNodes = new ArrayList<>();
        for (JexlNode orNode : orNodesWithMarkers) {
            List<JexlNode> newOrNodeChildren = new ArrayList<>();
            List<JexlNode> otherOrNodeChildren = new ArrayList<>();
            for (JexlNode orNodeChild : JexlNodes.children(JexlASTHelper.dereference(orNode))) {
                // distribute the nodes when we encounter an EQ node which could be used for field mapping
                if (orNodeChild instanceof ASTEQNode && isFieldValueTerm((ASTEQNode) orNodeChild)) {
                    List<JexlNode> newChildren = new ArrayList<>();
                    for (JexlNode newChild : nodesToDistribute) {
                        newChildren.add(RebuildingVisitor.copy(newChild));
                    }
                    newChildren.add(RebuildingVisitor.copy(orNodeChild));
                    newOrNodeChildren.add(createUnwrappedAndNode(newChildren));
                }
                // group all of the nodes which can't be used for field mapping separately, and handle them below
                else {
                    otherOrNodeChildren.add(RebuildingVisitor.copy(orNodeChild));
                }
            }
            
            // any nodes which won't contribute to updated field mappings are grouped together before
            // the distributed nodes are applied (to avoid unnecessary duplication of nodes)
            if (!otherOrNodeChildren.isEmpty()) {
                List<JexlNode> newChildren = new ArrayList<>();
                for (JexlNode newChild : nodesToDistribute) {
                    newChildren.add(RebuildingVisitor.copy(newChild));
                }
                newChildren.add(createUnwrappedOrNode(otherOrNodeChildren));
                newOrNodeChildren.add(createUnwrappedAndNode(newChildren));
            }
            
            newOrNodes.add(createUnwrappedOrNode(newOrNodeChildren));
        }
        
        return createUnwrappedAndNode(newOrNodes);
    }
    
    @Override
    public Object visit(ASTEQNode node, Object data) {
        return visitLeafNode(node, data);
    }
    
    @Override
    public Object visit(ASTLTNode node, Object data) {
        return visitLeafNode(node, data);
    }
    
    @Override
    public Object visit(ASTGTNode node, Object data) {
        return visitLeafNode(node, data);
    }
    
    @Override
    public Object visit(ASTLENode node, Object data) {
        return visitLeafNode(node, data);
    }
    
    @Override
    public Object visit(ASTGENode node, Object data) {
        return visitLeafNode(node, data);
    }
    
    @Override
    public Object visit(ASTERNode node, Object data) {
        return visitLeafNode(node, data);
    }
    
    @Override
    public Object visit(ASTNRNode node, Object data) {
        return visitLeafNode(node, data);
    }
    
    @Override
    public Object visit(ASTFunctionNode node, Object data) {
        return visitLeafNode(node, data);
    }
    
    // let's avoid != for now
    @Override
    public Object visit(ASTNENode node, Object data) {
        return RebuildingVisitor.copy(node);
    }
    
    // let's avoid not nodes for now
    @Override
    public Object visit(ASTNotNode node, Object data) {
        return RebuildingVisitor.copy(node);
    }
    
    // if the node is marked, only descend into delayed predicates or bounded ranges
    @Override
    public Object visit(ASTReference node, Object data) {
        if (QueryPropertyMarkerVisitor.instanceOfAnyExcept(node, Lists.newArrayList(ASTDelayedPredicate.class, BoundedRange.class))) {
            return RebuildingVisitor.copy(node);
        }
        
        return super.visit(node, data);
    }
    
    // if the node is marked, only descend into delayed predicates or bounded ranges
    @Override
    public Object visit(ASTReferenceExpression node, Object data) {
        if (QueryPropertyMarkerVisitor.instanceOfAnyExcept(node, Lists.newArrayList(ASTDelayedPredicate.class, BoundedRange.class))) {
            return RebuildingVisitor.copy(node);
        }
        
        return super.visit(node, data);
    }
    
    /**
     * Attempts to create whindexes using both the leaf nodes and the anded nodes from our ancestors. Each of the whindexes created must contain at least one of
     * the leaf nodes in order to be valid. The used leaf nodes are passed back via the usedLeafNodes param. The used anded nodes are passed back via the
     * parentData.
     *
     * @param parentData
     *            Contains the ancestor anded nodes, anded nodes used to create the returned whindexes, and a flag indicating whether whindexes were found
     * @param nonLeafNodes
     *            A collection of non-leaf child nodes, from the parent node
     * @param leafNodes
     *            A multimap of leaf child nodes, keyed by field name, from the parent node
     * @param usedLeafNodes
     *            A multimap of used leaf child nodes, keyed by field name, used to create the returned whindexes
     * @return A list of modified and unmodified non-leaf child nodes, from the parent node
     */
    private List<JexlNode> processNonLeafNodes(ExpandData parentData, Collection<JexlNode> nonLeafNodes, Multimap<String,JexlNode> leafNodes,
                    Multimap<String,JexlNode> usedLeafNodes) {
        // descend into the child nodes, passing the anded leaf nodes down,
        // in order to determine whether or not whindexes can be made
        List<JexlNode> unmodifiedNodes = new ArrayList<>();
        List<JexlNode> modifiedNodes = new ArrayList<>();
        List<String> modifiedNodesAsString = new ArrayList<>();
        for (JexlNode nonLeafNode : nonLeafNodes) {
            ExpandData eData = new ExpandData();
            
            // add the anded leaf nodes from our ancestors
            eData.andedNodes.putAll(parentData.andedNodes);
            
            // add our anded leaf nodes
            eData.andedNodes.putAll(leafNodes);
            
            // descend into the non-leaf node to see if whindexes can be made
            JexlNode processedNode = (JexlNode) nonLeafNode.jjtAccept(this, eData);
            String processedNodeAsString = JexlStringBuildingVisitor.buildQuery(processedNode);
            
            // if whindexes were made, save the processed node, and determine which leaf
            // nodes were used from this and node (if any)
            if (eData.foundWhindex) {
                // it is possible that multiple whindex combinations map to
                // the same final node. use this check to eliminate dupes
                if (!modifiedNodesAsString.contains(processedNodeAsString)) {
                    parentData.foundWhindex = true;
                    modifiedNodes.add(processedNode);
                    modifiedNodesAsString.add(processedNodeAsString);
                    for (Map.Entry<String,JexlNode> usedAndedNode : eData.usedAndedNodes.entries())
                        if (leafNodes.containsEntry(usedAndedNode.getKey(), usedAndedNode.getValue()))
                            usedLeafNodes.put(usedAndedNode.getKey(), usedAndedNode.getValue());
                        else
                            parentData.usedAndedNodes.put(usedAndedNode.getKey(), usedAndedNode.getValue());
                }
            } else
                unmodifiedNodes.add(nonLeafNode);
        }
        
        // add unmodified nodes, then modified nodes
        List<JexlNode> processedNodes = new ArrayList<>();
        processedNodes.addAll(unmodifiedNodes);
        processedNodes.addAll(modifiedNodes);
        
        return processedNodes;
    }
    
    /**
     * Attempts to create whindexes using the remaining leaf and anded nodes from our ancestors. Each of the whindexes created must contain at least one of the
     * leaf nodes in order to be valid. The used leaf nodes are passed back via the usedLeafNodes param. The used anded nodes are passed back via the
     * parentData.
     *
     * @param parentData
     *            Contains the ancestor anded nodes, anded nodes used to create the returned whindexes, and a flag indicating whether whindexes were found
     * @param leafNodes
     *            A multimap of leaf child nodes, keyed by field name, from the parent node
     * @param usedLeafNodes
     *            A multimap of used leaf child nodes, keyed by field name, used to create the returned whindexes
     * @return A list of modified and unmodified leaf child nodes, from the parent node
     */
    private List<JexlNode> processUnusedLeafNodes(ExpandData parentData, Multimap<String,JexlNode> leafNodes, Multimap<String,JexlNode> usedLeafNodes,
                    List<WhindexTerm> whindexTerms) {
        // use the remaining leaf and anded nodes to generate whindexes
        // note: the used leaf and anded nodes are removed in 'processNonLeafNodes'
        List<WhindexTerm> foundWhindexes = findWhindex(leafNodes, parentData.andedNodes, usedLeafNodes, parentData.usedAndedNodes);
        
        List<JexlNode> whindexLeafNodes = new ArrayList<>();
        
        // if we found some whindexes
        if (!foundWhindexes.isEmpty()) {
            List<JexlNode> whindexNodes = new ArrayList<>();
            for (WhindexTerm whindexTerm : foundWhindexes) {
                JexlNode whindexNode = whindexTerm.createWhindexNode();
                whindexNodes.add(whindexNode);
                jexlNodeToWhindexMap.put(JexlASTHelper.dereference(whindexNode), whindexTerm);
                whindexTerms.add(whindexTerm);
            }
            
            // add the whindex nodes to our list of processed nodes
            if (!whindexNodes.isEmpty()) {
                parentData.foundWhindex = true;
                
                whindexLeafNodes.add(createUnwrappedAndNode(whindexNodes));
            }
        }
        
        leafNodes.values().removeAll(usedLeafNodes.values());
        
        List<JexlNode> unmodifiedLeafNodes = new ArrayList<>();
        List<JexlNode> modifiedLeafNodes = new ArrayList<>();
        
        // finally, for any remaining leaf nodes at this level, visit
        // them, and add them to our list of processed nodes
        for (JexlNode remainingLeafNode : leafNodes.values()) {
            ExpandData eData = new ExpandData();
            JexlNode processedNode = (JexlNode) remainingLeafNode.jjtAccept(this, eData);
            
            if (eData.foundWhindex) {
                parentData.foundWhindex = true;
                modifiedLeafNodes.add(processedNode);
            } else {
                unmodifiedLeafNodes.add(remainingLeafNode);
            }
        }
        
        List<JexlNode> processedLeafNodes = new ArrayList<>();
        processedLeafNodes.addAll(unmodifiedLeafNodes);
        processedLeafNodes.addAll(modifiedLeafNodes);
        processedLeafNodes.addAll(whindexLeafNodes);
        
        return processedLeafNodes;
    }
    
    private JexlNode visitLeafNode(JexlNode node, Object data) {
        if (data instanceof ExpandData)
            return visitLeafNode(node, (ExpandData) data);
        return node;
    }
    
    /**
     * The default leaf node visitor, which uses the anded nodes to determine whether a whindex can be formed with this leaf node.
     *
     * @param node
     *            A leaf node from the original script
     * @param eData
     *            ExpandData, containing ancestor anded nodes, used anded nodes, and a flag indicating whether whindexes were found
     * @return Returns a whindex node if one can be made, otherwise returns the original node
     */
    private JexlNode visitLeafNode(JexlNode node, ExpandData eData) {
        Set<String> fieldNames = new HashSet<>();
        JexlASTHelper.getIdentifiers(node).forEach(x -> fieldNames.add(JexlASTHelper.getIdentifier(x)));
        
        JexlNode resultNode = node;
        
        if (fieldNames.size() == 1) {
            String fieldName = Iterables.getOnlyElement(fieldNames);
            
            Multimap<String,JexlNode> leafNodes = LinkedHashMultimap.create();
            leafNodes.put(fieldName, node);
            
            List<WhindexTerm> foundWhindexes = findWhindex(leafNodes, eData.andedNodes, HashMultimap.create(), eData.usedAndedNodes);
            
            // if whindexes were found, create JexlNodes from them
            if (!foundWhindexes.isEmpty()) {
                List<JexlNode> whindexNodes = new ArrayList<>();
                for (WhindexTerm whindexTerm : foundWhindexes) {
                    JexlNode whindexNode = whindexTerm.createWhindexNode();
                    whindexNodes.add(whindexNode);
                    jexlNodeToWhindexMap.put(JexlASTHelper.dereference(whindexNode), whindexTerm);
                }
                
                if (!whindexNodes.isEmpty()) {
                    eData.foundWhindex = true;
                    resultNode = createUnwrappedAndNode(whindexNodes);
                }
            }
        }
        
        return resultNode;
    }
    
    /**
     * Using the leaf nodes and anded nodes passed in, attempts to create whindexes from those nodes. The generated whindexes are required to contain at least
     * one of the leaf nodes.
     *
     * @param leafNodes
     *            A multimap of leaf child nodes, keyed by field name, from the parent node
     * @param andedNodes
     *            A multimap of anded nodes, keyed by field name, passed down from our ancestors
     * @param usedLeafNodes
     *            A multimap of used leaf child nodes, keyed by field name, used to create the returned whindexes
     * @param usedAndedNodes
     *            A multimap of used anded nodes, keyed by field name, used to create the returned whindexes
     * @return A list of whindexes which can be created from the given leaf and anded nodes
     */
    private List<WhindexTerm> findWhindex(Multimap<String,JexlNode> leafNodes, Multimap<String,JexlNode> andedNodes, Multimap<String,JexlNode> usedLeafNodes,
                    Multimap<String,JexlNode> usedAndedNodes) {
        List<WhindexTerm> whindexTerms = new ArrayList<>();
        
        // once a leaf node is used to create a whindex, take it out of the rotation
        Multimap<String,JexlNode> remainingLeafNodes = LinkedHashMultimap.create();
        remainingLeafNodes.putAll(leafNodes);
        
        // once an anded node is used to create a whindex, take it out of the rotation
        Multimap<String,JexlNode> remainingAndedNodes = LinkedHashMultimap.create();
        remainingAndedNodes.putAll(andedNodes);
        
        Set<String> mappableFields = valueSpecificFieldMappings.entrySet().stream().flatMap(x -> x.getValue().keySet().stream()).collect(Collectors.toSet());
        
        // for each of the leaf nodes, what can we make?
        for (Map.Entry<String,JexlNode> leafEntry : leafNodes.entries()) {
            // is it a field/value match?
            if (leafEntry.getValue() instanceof ASTEQNode && isFieldValueTerm((ASTEQNode) leafEntry.getValue())) {
                String value = getStringValue(leafEntry.getValue());
                
                if (value != null) {
                    // do we have the required field we need?
                    Set<String> requiredFields = valueSpecificFieldMappings.get(value).keySet();
                    
                    List<Map.Entry<String,JexlNode>> mappedFieldMatches = new ArrayList<>();
                    remainingLeafNodes.entries().stream().filter(x -> requiredFields.contains(x.getKey())).forEach(mappedFieldMatches::add);
                    remainingAndedNodes.entries().stream().filter(x -> requiredFields.contains(x.getKey())).forEach(mappedFieldMatches::add);
                    
                    for (Map.Entry<String,JexlNode> mappedFieldMatch : mappedFieldMatches) {
                        whindexTerms.add(new WhindexTerm(leafEntry.getValue(), mappedFieldMatch.getValue(), valueSpecificFieldMappings.get(value).get(
                                        mappedFieldMatch.getKey())));
                        
                        if (leafNodes.containsEntry(mappedFieldMatch.getKey(), mappedFieldMatch.getValue())) {
                            remainingLeafNodes.remove(mappedFieldMatch.getKey(), mappedFieldMatch.getValue());
                            usedLeafNodes.put(mappedFieldMatch.getKey(), mappedFieldMatch.getValue());
                        } else {
                            remainingAndedNodes.remove(mappedFieldMatch.getKey(), mappedFieldMatch.getValue());
                            usedAndedNodes.put(mappedFieldMatch.getKey(), mappedFieldMatch.getValue());
                        }
                        
                        remainingLeafNodes.remove(leafEntry.getKey(), leafEntry.getValue());
                        usedLeafNodes.put(leafEntry.getKey(), leafEntry.getValue());
                    }
                }
            }
            // if it's not a term/value match, is it a mappable field?
            else if (mappableFields.contains(leafEntry.getKey())) {
                // if it's a mappable field, do we have the term/value match needed to map it?
                List<Map.Entry<String,JexlNode>> fieldValueMatches = new ArrayList<>();
                remainingLeafNodes.entries().stream()
                                .filter(x -> x.getValue() instanceof ASTEQNode && isFieldValueMatch((ASTEQNode) x.getValue(), leafEntry.getKey()))
                                .forEach(fieldValueMatches::add);
                remainingAndedNodes.entries().stream()
                                .filter(x -> x.getValue() instanceof ASTEQNode && isFieldValueMatch((ASTEQNode) x.getValue(), leafEntry.getKey()))
                                .forEach(fieldValueMatches::add);
                
                for (Map.Entry<String,JexlNode> fieldValueMatch : fieldValueMatches) {
                    String value = getStringValue(fieldValueMatch.getValue());
                    
                    if (value != null) {
                        whindexTerms.add(new WhindexTerm(fieldValueMatch.getValue(), leafEntry.getValue(), valueSpecificFieldMappings.get(value).get(
                                        leafEntry.getKey())));
                        
                        if (leafNodes.containsEntry(fieldValueMatch.getKey(), fieldValueMatch.getValue())) {
                            remainingLeafNodes.remove(fieldValueMatch.getKey(), fieldValueMatch.getValue());
                            usedLeafNodes.put(fieldValueMatch.getKey(), fieldValueMatch.getValue());
                        } else {
                            remainingAndedNodes.remove(fieldValueMatch.getKey(), fieldValueMatch.getValue());
                            usedAndedNodes.put(fieldValueMatch.getKey(), fieldValueMatch.getValue());
                        }
                        
                        remainingLeafNodes.remove(leafEntry.getKey(), leafEntry.getValue());
                        usedLeafNodes.put(leafEntry.getKey(), leafEntry.getValue());
                    }
                }
            }
        }
        
        return whindexTerms;
    }
    
    private boolean isFieldValueMatch(ASTEQNode eqNode, String mappableField) {
        String field = getStringField(eqNode);
        String value = getStringValue(eqNode);
        // if this is a term/value match
        return mappingFields.contains(field) && valueSpecificFieldMappings.containsKey(value)
                        && valueSpecificFieldMappings.get(value).containsKey(mappableField);
    }
    
    private boolean isFieldValueTerm(ASTEQNode eqNode) {
        String field = getStringField(eqNode);
        String value = getStringValue(eqNode);
        // if this is a term/value match
        return mappingFields.contains(field) && valueSpecificFieldMappings.containsKey(value);
    }
    
    /**
     * This method checks each of the child nodes, and returns those which are leaf nodes. Range nodes are also considered leaf nodes for our purposes. If the
     * root node is a range node, then that node will be returned. Reference, ReferenceExpression, and 'and' or 'or' nodes with a single child are passed
     * through in search of the actual leaf node.
     *
     * @param rootNode
     *            The node whose children we will check
     * @param otherNodes
     *            Non-leaf child nodes of the root node
     * @return A multimap of field name to leaf node
     */
    private Multimap<String,JexlNode> getLeafNodes(JexlNode rootNode, Collection<JexlNode> otherNodes) {
        Multimap<String,JexlNode> childrenLeafNodes = ArrayListMultimap.create();
        
        if (rootNode instanceof ASTAndNode) {
            // check to see if this node is a range node, if so, this is our leaf node
            JexlNode leafKid = getLeafNode(rootNode);
            if (leafKid != null) {
                String kidFieldName;
                LiteralRange range = JexlASTHelper.findRange().getRange(leafKid);
                kidFieldName = (range != null) ? range.getFieldName() : JexlASTHelper.getIdentifier(leafKid);
                childrenLeafNodes.put(kidFieldName, rootNode);
            }
        }
        
        if (childrenLeafNodes.isEmpty()) {
            for (JexlNode child : JexlNodes.children(rootNode)) {
                JexlNode leafKid = getLeafNode(child);
                if (leafKid != null) {
                    Set<String> kidFieldNames = new LinkedHashSet<>();
                    LiteralRange range = JexlASTHelper.findRange().getRange(leafKid);
                    if (range != null) {
                        kidFieldNames.add(range.getFieldName());
                    } else {
                        if (leafKid instanceof ASTEQNode) {
                            kidFieldNames.add(JexlASTHelper.getIdentifier(leafKid));
                        } else if (leafKid instanceof ASTFunctionNode) {
                            JexlArgumentDescriptor descriptor = JexlFunctionArgumentDescriptorFactory.F.getArgumentDescriptor((ASTFunctionNode) leafKid);
                            if (descriptor instanceof GeoWaveFunctionsDescriptor.GeoWaveJexlArgumentDescriptor) {
                                kidFieldNames.addAll(descriptor.fields(metadataHelper, Collections.emptySet()));
                            } else {
                                if (otherNodes != null) {
                                    otherNodes.add(child);
                                }
                            }
                        }
                    }
                    for (String kidFieldName : kidFieldNames) {
                        // note: we save the actual direct sibling of the and node, including
                        // any reference nodes. those will be trimmed off later
                        childrenLeafNodes.put(kidFieldName, child);
                    }
                } else {
                    if (otherNodes != null)
                        otherNodes.add(child);
                }
            }
        }
        return childrenLeafNodes;
    }
    
    /**
     * This method is used to find leaf nodes. Reference, ReferenceExpression, and 'and' or 'or' nodes with a single child are passed through in search of the
     * actual leaf node.
     *
     * @param node
     *            The node whose children we will check
     * @return The found leaf node, or null
     */
    private JexlNode getLeafNode(JexlNode node) {
        if (node instanceof ASTReference) {
            return getLeafNode((ASTReference) node);
        }
        
        if (node instanceof ASTReferenceExpression) {
            return getLeafNode((ASTReferenceExpression) node);
        }
        
        if (node instanceof ASTAndNode) {
            return getLeafNode((ASTAndNode) node);
        }
        
        if (node instanceof ASTEQNode || node instanceof ASTFunctionNode) {
            return node;
        }
        
        return null;
    }
    
    /**
     * This method is used to find leaf nodes. Reference, ReferenceExpression, and 'and' or 'or' nodes with a single child are passed through in search of the
     * actual leaf node.
     *
     * @param node
     *            The node whose children we will check
     * @return The found leaf node, or null
     */
    private JexlNode getLeafNode(ASTReference node) {
        // ignore marked nodes
        if (!QueryPropertyMarkerVisitor.instanceOfAnyExcept(node, Collections.singletonList(BoundedRange.class))) {
            if (node.jjtGetNumChildren() == 1) {
                JexlNode kid = node.jjtGetChild(0);
                if (kid instanceof ASTReferenceExpression) {
                    return getLeafNode((ASTReferenceExpression) kid);
                } else if (kid instanceof ASTFunctionNode) {
                    return getLeafNode((ASTFunctionNode) kid);
                }
            }
        }
        return null;
    }
    
    /**
     * This method is used to find leaf nodes. Reference, ReferenceExpression, and 'and' or 'or' nodes with a single child are passed through in search of the
     * actual leaf node.
     *
     * @param node
     *            The node whose children we will check
     * @return The found leaf node, or null
     */
    private JexlNode getLeafNode(ASTReferenceExpression node) {
        // ignore marked nodes
        if (!QueryPropertyMarkerVisitor.instanceOfAnyExcept(node, Collections.singletonList(BoundedRange.class))) {
            if (node != null && node.jjtGetNumChildren() == 1) {
                JexlNode kid = node.jjtGetChild(0);
                if (kid instanceof ASTAndNode) {
                    return getLeafNode((ASTAndNode) kid);
                } else if (kid instanceof ASTEQNode || kid instanceof ASTFunctionNode) {
                    return kid;
                } else {
                    return getLeafNode(kid);
                }
            }
        }
        
        return null;
    }
    
    /**
     * This method is used to find leaf nodes. Reference, ReferenceExpression, and 'and' or 'or' nodes with a single child are passed through in search of the
     * actual leaf node.
     *
     * @param node
     *            The node whose children we will check
     * @return The found leaf node, or null
     */
    private JexlNode getLeafNode(ASTAndNode node) {
        // ignore marked nodes
        if (!QueryPropertyMarkerVisitor.instanceOfAnyExcept(node, Collections.singletonList(BoundedRange.class))) {
            if (node.jjtGetNumChildren() == 1) {
                return getLeafNode(node.jjtGetChild(0));
            } else if (BoundedRange.instanceOf(node)) {
                return node;
            }
        }
        return null;
    }
    
    /**
     * This is a helper method which will attempt to create an 'and' node from the given jexl nodes. If a single node is passed, we will just return that node
     * instead of creating an unnecessary 'and' wrapper node.
     *
     * @param jexlNodes
     *            The nodes to 'and' together
     * @return An 'and' node comprised of the jexlNodes, or if a single jexlNode was passed in, we simply return that node.
     */
    private static JexlNode createUnwrappedAndNode(Collection<JexlNode> jexlNodes) {
        if (jexlNodes != null && !jexlNodes.isEmpty()) {
            if (jexlNodes.size() == 1)
                return jexlNodes.stream().findFirst().get();
            else
                return JexlNodeFactory.createUnwrappedAndNode(jexlNodes);
        }
        return null;
    }
    
    /**
     * This is a helper method which will attempt to create an 'or' node from the given jexl nodes. If a single node is passed, we will just return that node
     * instead of creating an unnecessary 'or' wrapper node.
     *
     * @param jexlNodes
     *            The nodes to 'or' together
     * @return An 'or' node comprised of the jexlNodes, or if a single jexlNode was passed in, we simply return that node.
     */
    private static JexlNode createUnwrappedOrNode(Collection<JexlNode> jexlNodes) {
        if (jexlNodes != null && !jexlNodes.isEmpty()) {
            if (jexlNodes.size() == 1)
                return jexlNodes.stream().findFirst().get();
            else
                return JexlNodeFactory.createUnwrappedOrNode(jexlNodes);
        }
        return null;
    }
    
    private String getStringField(JexlNode jexlNode) {
        String field = null;
        if (jexlNode != null) {
            field = JexlASTHelper.getIdentifier(jexlNode);
        }
        return field;
    }
    
    private String getStringValue(JexlNode jexlNode) {
        String value = null;
        if (jexlNode != null) {
            try {
                Object objValue = JexlASTHelper.getLiteralValue(jexlNode);
                if (objValue != null) {
                    value = objValue.toString();
                }
            } catch (Exception e) {
                if (log.isTraceEnabled()) {
                    log.trace("Couldn't get value from jexl node: " + JexlStringBuildingVisitor.buildQuery(jexlNode));
                }
            }
        }
        return value;
    }
    
    private static class SplitGeoWaveFunctionVisitor extends RebuildingVisitor {
        @SuppressWarnings("unchecked")
        public static <T extends JexlNode> T apply(T script) {
            SplitGeoWaveFunctionVisitor visitor = new SplitGeoWaveFunctionVisitor();
            return (T) script.jjtAccept(visitor, null);
        }
        
        @Override
        public Object visit(ASTFunctionNode node, Object data) {
            JexlArgumentDescriptor descriptor = JexlFunctionArgumentDescriptorFactory.F.getArgumentDescriptor(node);
            if (descriptor instanceof GeoWaveFunctionsDescriptor.GeoWaveJexlArgumentDescriptor) {
                List<ASTIdentifier> identifiers = JexlASTHelper.getIdentifiers(node);
                if (identifiers.size() > 1) {
                    List<JexlNode> functionNodes = new ArrayList<>();
                    
                    FunctionJexlNodeVisitor functionVisitor = new FunctionJexlNodeVisitor();
                    node.jjtAccept(functionVisitor, null);
                    
                    for (ASTIdentifier identifier : identifiers) {
                        List<JexlNode> args = new ArrayList<>();
                        for (JexlNode arg : functionVisitor.args()) {
                            args.add(RebuildingVisitor.copy(arg));
                        }
                        args.set(0, RebuildingVisitor.copy(identifier));
                        
                        functionNodes.add(JexlNodes.makeRef(FunctionJexlNodeVisitor.makeFunctionFrom(functionVisitor.namespace(), functionVisitor.name(),
                                        args.toArray(new JexlNode[0]))));
                    }
                    return JexlNodeFactory.createUnwrappedOrNode(functionNodes);
                }
            }
            return super.visit(node, data);
        }
    }
    
    /**
     * This is a visitor which is used to fully distribute anded nodes into a given node. The visitor will only distribute the anded nodes to those descendant
     * nodes within the tree with which they are not already anded (via a whindex).
     *
     */
    private static class DistributeAndedNodes extends RebuildingVisitor {
        private JexlNode initialNode = null;
        private final List<JexlNode> andedNodes;
        private final Map<JexlNode,WhindexTerm> whindexNodes;
        
        private static class DistAndData {
            Set<JexlNode> usedAndedNodes = new HashSet<>();
        }
        
        private DistributeAndedNodes(List<JexlNode> andedNodes, Map<JexlNode,WhindexTerm> whindexNodes) {
            this.andedNodes = andedNodes;
            this.whindexNodes = whindexNodes;
        }
        
        /**
         * Distribute the anded node, making sure to 'and' it in at the highest possible level of the tree. This version takes a map of whindex nodes to their
         * component nodes, so that we can better check whindex nodes to see if they already include the anded node. That is to say, we will not 'and' a whindex
         * node with one of it's component nodes.
         *
         * @param script
         *            The node that we will be distributing the anded nodes into
         * @param andedNodes
         *            The nodes which we will be distributing into the root node
         * @param whindexNodes
         *            A map of generated whindex jexl nodes to the whindex term used to create that node
         * @return An updated script with the anded nodes distributed throughout
         */
        public static JexlNode distributeAndedNode(JexlNode script, List<JexlNode> andedNodes, Map<JexlNode,WhindexTerm> whindexNodes) {
            DistributeAndedNodes visitor = new DistributeAndedNodes(andedNodes, whindexNodes);
            DistributeAndedNodes.DistAndData foundData = new DistributeAndedNodes.DistAndData();
            JexlNode resultNode = (JexlNode) script.jjtAccept(visitor, foundData);
            
            if (!foundData.usedAndedNodes.containsAll(andedNodes)) {
                List<JexlNode> nodes = andedNodes.stream().filter(node -> !foundData.usedAndedNodes.contains(node)).map(RebuildingVisitor::copy)
                                .collect(Collectors.toList());
                nodes.add(resultNode);
                
                return createUnwrappedAndNode(nodes);
            }
            
            return resultNode;
        }
        
        /**
         * Checks each of the child nodes, and determines how the anded nodes should be applied.
         *
         * @param node
         *            The node that we will be distributing the anded nodes into
         * @param data
         *            The nodes which we will be distributing into the root node
         * @return An updated script with the anded nodes distributed throughout
         */
        @Override
        public Object visit(ASTOrNode node, Object data) {
            DistributeAndedNodes.DistAndData parentData = (DistributeAndedNodes.DistAndData) data;
            
            if (initialNode == null || initialNode instanceof ASTReference || initialNode instanceof ASTReferenceExpression)
                initialNode = node;
            
            // if this node is one of the anded nodes, or a whindex
            // comprised of one of the anded nodes, halt recursion
            List<JexlNode> usedAndedNodes = usesAndedNodes(node);
            if (!usedAndedNodes.isEmpty()) {
                parentData.usedAndedNodes.addAll(usedAndedNodes);
                return node;
            }
            
            // don't descend into whindex nodes, and don't copy them
            // this logic is dependent upon identifying whindex nodes by their address
            if (whindexNodes.containsKey(node)) {
                return node;
            }
            
            boolean rebuildNode = false;
            
            // check each child node
            List<JexlNode> nodesMissingEverything = new ArrayList<>();
            List<JexlNode> nodesWithEverything = new ArrayList<>();
            Map<JexlNode,List<JexlNode>> nodesMissingSomething = new LinkedHashMap<>();
            for (JexlNode child : JexlNodes.children(node)) {
                DistributeAndedNodes.DistAndData foundData = new DistributeAndedNodes.DistAndData();
                JexlNode processedChild = (JexlNode) child.jjtAccept(this, foundData);
                
                if (processedChild != child)
                    rebuildNode = true;
                
                if (foundData.usedAndedNodes.isEmpty())
                    nodesMissingEverything.add(processedChild);
                else if (!foundData.usedAndedNodes.containsAll(andedNodes)) {
                    List<JexlNode> missingAndedNodes = new ArrayList<>(andedNodes);
                    missingAndedNodes.removeAll(foundData.usedAndedNodes);
                    nodesMissingSomething.put(processedChild, missingAndedNodes);
                } else
                    nodesWithEverything.add(processedChild);
            }
            
            // if none of the children are missing anything, we're done
            if (nodesWithEverything.size() == node.jjtGetNumChildren()) {
                parentData.usedAndedNodes.addAll(andedNodes);
                if (rebuildNode)
                    return createUnwrappedOrNode(nodesWithEverything);
                else
                    return node;
            }
            // if all of the children are missing everything, we're done
            // note: we shouldn't need to rebuild the or node because if the children
            // are missing everything, it implies that the children were left as-is
            else if (nodesMissingEverything.size() == node.jjtGetNumChildren()) {
                return node;
            }
            
            // if we got here, then there are some nodes missing SOMETHING, and we have work to do
            List<JexlNode> rebuiltChildren = new ArrayList<>();
            
            // for children missing at least one andedNode -> go through each one, and make a new call to 'distributeAndedNode' passing only the missing
            // andedNodes
            for (Map.Entry<JexlNode,List<JexlNode>> childEntry : nodesMissingSomething.entrySet())
                rebuiltChildren.add(DistributeAndedNodes.distributeAndedNode(childEntry.getKey(), childEntry.getValue(), whindexNodes));
            
            // for children missing everything -> 'or' them together, then 'and' them with the full set of andedNodes
            List<JexlNode> nodeList = andedNodes.stream().map(RebuildingVisitor::copy).collect(Collectors.toList());
            
            if (!nodesMissingEverything.isEmpty()) {
                nodeList.add(createUnwrappedOrNode(nodesMissingEverything));
            }
            
            rebuiltChildren.add(createUnwrappedAndNode(nodeList));
            
            // for children with everything -> keep those as-is
            rebuiltChildren.addAll(nodesWithEverything);
            
            parentData.usedAndedNodes.addAll(andedNodes);
            
            return createUnwrappedOrNode(rebuiltChildren);
        }
        
        /**
         * Checks each of the child nodes, and determines how the anded nodes should be applied.
         *
         * @param node
         *            The node that we will be distributing the anded nodes into
         * @param data
         *            The nodes which we will be distributing into the root node
         * @return An updated script with the anded nodes distributed throughout
         */
        @Override
        public Object visit(ASTAndNode node, Object data) {
            DistributeAndedNodes.DistAndData parentData = (DistributeAndedNodes.DistAndData) data;
            
            if (initialNode == null || initialNode instanceof ASTReference || initialNode instanceof ASTReferenceExpression)
                initialNode = node;
            
            // if this node is one of the anded nodes, or a whindex
            // comprised of one of the anded nodes, halt recursion
            List<JexlNode> usedAndedNodes = usesAndedNodes(node);
            if (!usedAndedNodes.isEmpty()) {
                parentData.usedAndedNodes.addAll(usedAndedNodes);
                return node;
            }
            
            // don't descend into whindex nodes, and don't copy them
            // this logic is dependent upon identifying whindex nodes by their address
            if (whindexNodes.containsKey(node)) {
                return node;
            }
            
            // check each child node to see how many of the desired andedNodes are present
            List<JexlNode> rebuiltChildren = new ArrayList<>();
            for (JexlNode child : JexlNodes.children(node)) {
                DistributeAndedNodes.DistAndData foundData = new DistributeAndedNodes.DistAndData();
                rebuiltChildren.add((JexlNode) child.jjtAccept(this, foundData));
                
                parentData.usedAndedNodes.addAll(foundData.usedAndedNodes);
            }
            
            // are some anded nodes missing, and is this the initial node?
            if (!parentData.usedAndedNodes.containsAll(andedNodes) && node.equals(initialNode)) {
                // 'and' with the missing anded nodes, and return
                List<JexlNode> nodes = andedNodes.stream().filter(andedNode -> !parentData.usedAndedNodes.contains(andedNode)).map(RebuildingVisitor::copy)
                                .collect(Collectors.toList());
                nodes.add(node);
                
                // this is probably unnecessary, but to be safe, let's set it
                parentData.usedAndedNodes.addAll(andedNodes);
                
                return createUnwrappedAndNode(nodes);
            }
            
            return createUnwrappedAndNode(rebuiltChildren);
        }
        
        @Override
        public Object visit(ASTEQNode node, Object data) {
            visitInternal(node, data);
            return node;
        }
        
        @Override
        public Object visit(ASTNENode node, Object data) {
            visitInternal(node, data);
            return node;
        }
        
        @Override
        public Object visit(ASTLTNode node, Object data) {
            visitInternal(node, data);
            return node;
        }
        
        @Override
        public Object visit(ASTGTNode node, Object data) {
            visitInternal(node, data);
            return node;
        }
        
        @Override
        public Object visit(ASTLENode node, Object data) {
            visitInternal(node, data);
            return node;
        }
        
        @Override
        public Object visit(ASTGENode node, Object data) {
            visitInternal(node, data);
            return node;
        }
        
        @Override
        public Object visit(ASTERNode node, Object data) {
            visitInternal(node, data);
            return node;
        }
        
        @Override
        public Object visit(ASTNRNode node, Object data) {
            visitInternal(node, data);
            return node;
        }
        
        @Override
        public Object visit(ASTFunctionNode node, Object data) {
            visitInternal(node, data);
            return node;
        }
        
        @Override
        public Object visit(ASTReference node, Object data) {
            visitInternal(node, data);
            return super.visit(node, data);
        }
        
        @Override
        public Object visit(ASTReferenceExpression node, Object data) {
            visitInternal(node, data);
            return super.visit(node, data);
        }
        
        /**
         * Used to determine whether this is a whindex node, and if so, which of the anded nodes does it have as components
         *
         * @param node
         *            The node to check for anded components
         * @return A list of anded jexl nodes used to create the whindex node
         */
        private List<JexlNode> usesAndedNodes(JexlNode node) {
            List<JexlNode> usedAndedNodes = new ArrayList<>();
            for (JexlNode andedNode : andedNodes)
                if (whindexNodes.containsKey(node) && whindexNodes.get(node).contains(andedNode))
                    usedAndedNodes.add(andedNode);
            return usedAndedNodes;
        }
        
        private void visitInternal(JexlNode node, Object data) {
            if (initialNode == null || initialNode instanceof ASTReference || initialNode instanceof ASTReferenceExpression)
                initialNode = node;
            
            DistributeAndedNodes.DistAndData parentData = (DistributeAndedNodes.DistAndData) data;
            parentData.usedAndedNodes.addAll(usesAndedNodes(node));
        }
    }
    
    private static class WhindexTerm {
        
        private final JexlNode fieldValueNode;
        private final JexlNode mappableNode;
        private final String newFieldName;
        
        public WhindexTerm(JexlNode fieldValueNode, JexlNode mappableNode, String newFieldName) {
            this.fieldValueNode = fieldValueNode;
            this.mappableNode = mappableNode;
            this.newFieldName = newFieldName;
        }
        
        public JexlNode getFieldValueNode() {
            return fieldValueNode;
        }
        
        public JexlNode getMappableNode() {
            return mappableNode;
        }
        
        public JexlNode createWhindexNode() {
            JexlNode copiedNode = RebuildingVisitor.copy(mappableNode);
            List<ASTIdentifier> identifiers = JexlASTHelper.getIdentifiers(copiedNode);
            Set<String> fieldNames = new HashSet<>();
            
            identifiers.forEach(x -> fieldNames.add(JexlASTHelper.getIdentifier(x)));
            
            if (fieldNames.size() == 1) {
                for (ASTIdentifier identifier : identifiers) {
                    JexlNodes.replaceChild(identifier.jjtGetParent(), identifier, JexlNodes.makeIdentifierWithImage(newFieldName));
                }
            } else {
                throw new RuntimeException("Cannot create a Whindex term using multiple identifiers.");
            }
            
            return copiedNode;
        }
        
        public boolean contains(JexlNode node) {
            // @formatter:off
            return fieldValueNode == node || JexlASTHelper.dereference(fieldValueNode) == node ||
                    mappableNode == node || JexlASTHelper.dereference(mappableNode) == node;
            // @formatter:on
        }
    }
}
