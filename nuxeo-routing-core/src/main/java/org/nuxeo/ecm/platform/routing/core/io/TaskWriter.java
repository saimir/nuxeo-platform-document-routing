/*
 * (C) Copyright 2014-2015 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     <a href="mailto:grenard@nuxeo.com">Guillaume Renard</a>
 *
 */

package org.nuxeo.ecm.platform.routing.core.io;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static org.nuxeo.ecm.core.io.registry.reflect.Instantiations.SINGLETON;
import static org.nuxeo.ecm.core.io.registry.reflect.Priorities.REFERENCE;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import javax.inject.Inject;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.jboss.el.ExpressionFactoryImpl;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.io.marshallers.json.ExtensibleEntityJsonWriter;
import org.nuxeo.ecm.core.io.marshallers.json.OutputStreamWithJsonWriter;
import org.nuxeo.ecm.core.io.marshallers.json.document.DocumentModelJsonWriter;
import org.nuxeo.ecm.core.io.registry.MarshallerRegistry;
import org.nuxeo.ecm.core.io.registry.Writer;
import org.nuxeo.ecm.core.io.registry.context.RenderingContext;
import org.nuxeo.ecm.core.io.registry.context.RenderingContext.SessionWrapper;
import org.nuxeo.ecm.core.io.registry.reflect.Setup;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.types.CompositeType;
import org.nuxeo.ecm.core.schema.types.Field;
import org.nuxeo.ecm.core.schema.types.Schema;
import org.nuxeo.ecm.core.schema.utils.DateParser;
import org.nuxeo.ecm.platform.actions.ActionContext;
import org.nuxeo.ecm.platform.actions.ELActionContext;
import org.nuxeo.ecm.platform.actions.ejb.ActionManager;
import org.nuxeo.ecm.platform.el.ExpressionContext;
import org.nuxeo.ecm.platform.routing.api.DocumentRoute;
import org.nuxeo.ecm.platform.routing.api.DocumentRoutingConstants;
import org.nuxeo.ecm.platform.routing.core.impl.GraphNode;
import org.nuxeo.ecm.platform.routing.core.impl.GraphNode.Button;
import org.nuxeo.ecm.platform.routing.core.impl.GraphRoute;
import org.nuxeo.ecm.platform.task.Task;
import org.nuxeo.ecm.platform.task.TaskComment;
import org.nuxeo.runtime.api.Framework;

/**
 * @since 7.2
 */
@Setup(mode = SINGLETON, priority = REFERENCE)
public class TaskWriter extends ExtensibleEntityJsonWriter<Task> {

    @Inject
    private SchemaManager schemaManager;

    public TaskWriter() {
        super(ENTITY_TYPE, Task.class);
    }

    public static final String ENTITY_TYPE = "task";

    public void writeEntityBody(Task item, JsonGenerator jg) throws IOException {
        GraphRoute workflowInstance = null;
        GraphNode node = null;
        String workflowInstanceId = item.getProcessId();
        final String nodeId = item.getVariable(DocumentRoutingConstants.TASK_NODE_ID_KEY);
        try (SessionWrapper wrapper = ctx.getSession(item.getDocument())) {
            if (StringUtils.isNotBlank(workflowInstanceId)) {
                NodeAccessRunner nodeAccessRunner = new NodeAccessRunner(wrapper.getSession(), workflowInstanceId,
                        nodeId);
                nodeAccessRunner.runUnrestricted();
                workflowInstance = nodeAccessRunner.getWorkflowInstance();
                node = nodeAccessRunner.getNode();
            }

            jg.writeStringField("id", item.getDocument().getId());
            jg.writeStringField("name", item.getName());
            jg.writeStringField("workflowInstanceId", workflowInstanceId);
            if (workflowInstance != null) {
                jg.writeStringField("workflowModelName", workflowInstance.getModelName());
            }
            jg.writeStringField("state", item.getDocument().getCurrentLifeCycleState());
            jg.writeStringField("directive", item.getDirective());
            jg.writeStringField("created", DateParser.formatW3CDateTime(item.getCreated()));
            jg.writeStringField("dueDate", DateParser.formatW3CDateTime(item.getDueDate()));
            jg.writeStringField("nodeName", item.getVariable(DocumentRoutingConstants.TASK_NODE_ID_KEY));

            jg.writeArrayFieldStart("targetDocumentIds");
            for (String docId : item.getTargetDocumentsIds()) {
                jg.writeStartObject();
                jg.writeStringField("id", docId);
                jg.writeEndObject();
            }
            jg.writeEndArray();

            jg.writeArrayFieldStart("actors");
            for (String actorId : item.getActors()) {
                jg.writeStartObject();
                jg.writeStringField("id", actorId);
                jg.writeEndObject();
            }
            jg.writeEndArray();

            jg.writeArrayFieldStart("comments");
            for (TaskComment comment : item.getComments()) {
                jg.writeStartObject();
                jg.writeStringField("author", comment.getAuthor());
                jg.writeStringField("text", comment.getText());
                jg.writeStringField("date", DateParser.formatW3CDateTime(comment.getCreationDate().getTime()));
                jg.writeEndObject();
            }
            jg.writeEndArray();

            jg.writeFieldName("variables");
            jg.writeStartObject();
            // add nodeVariables
            writeTaskVariables(node, jg, registry, ctx, schemaManager);
            // add workflow variables
            if (workflowInstance != null) {
                writeWorkflowVariables(workflowInstance, node, jg, registry, ctx, schemaManager);
            }
            jg.writeEndObject();

            jg.writeFieldName("taskInfo");
            jg.writeStartObject();
            final ActionManager actionManager = Framework.getService(ActionManager.class);
            jg.writeArrayFieldStart("taskActions");
            for (Button button : node.getTaskButtons()) {
                if (StringUtils.isBlank(button.getFilter())
                        || actionManager.checkFilter(button.getFilter(), createActionContext(wrapper.getSession()))) {
                    jg.writeStartObject();
                    jg.writeStringField("name", button.getName());
                    jg.writeStringField("url",
                            ctx.getBaseUrl() + "api/v1/task/" + item.getDocument().getId() + "/" + button.getName());
                    jg.writeStringField("label", button.getLabel());
                    jg.writeEndObject();
                }
            }
            jg.writeEndArray();

            jg.writeFieldName("layoutResource");
            jg.writeStartObject();
            jg.writeStringField("name", node.getTaskLayout());
            jg.writeStringField("url", ctx.getBaseUrl() + "layout-manager/layouts/?layoutName=" + node.getTaskLayout());
            jg.writeEndObject();

            jg.writeArrayFieldStart("schemas");
            for (String schema : node.getDocument().getSchemas()) {
                // TODO only keep functional schema once adaptation done
                jg.writeStartObject();
                jg.writeStringField("name", schema);
                jg.writeStringField("url", ctx.getBaseUrl() + "api/v1/config/schemas/" + schema);
                jg.writeEndObject();
            }
            jg.writeEndArray();

            jg.writeEndObject();
        }

    }

    protected static ActionContext createActionContext(CoreSession session) {
        ActionContext actionContext = new ELActionContext(new ExpressionContext(), new ExpressionFactoryImpl());
        actionContext.setDocumentManager(session);
        actionContext.setCurrentPrincipal((NuxeoPrincipal) session.getPrincipal());
        return actionContext;
    }

    /**
     * @since 8.3
     */
    public static void writeTaskVariables(GraphNode node, JsonGenerator jg, MarshallerRegistry registry,
            RenderingContext ctx, SchemaManager schemaManager) throws IOException, JsonGenerationException {
        String facet = (String) node.getDocument().getPropertyValue(GraphNode.PROP_VARIABLES_FACET);
        if (StringUtils.isNotBlank(facet)) {

            CompositeType type = schemaManager.getFacet(facet);
            if (type != null) {
                boolean hasFacet = node.getDocument().hasFacet(facet);

                Writer<Property> propertyWriter = registry.getWriter(ctx, Property.class, APPLICATION_JSON_TYPE);
                // provides the current route to the property marshaller
                try (Closeable resource = ctx.wrap().with(DocumentModelJsonWriter.ENTITY_TYPE,
                        node.getDocument()).open()) {
                    for (Field f : type.getFields()) {
                        String name = f.getName().getLocalName();
                        Property property = hasFacet ? node.getDocument().getProperty(name) : null;
                        OutputStream out = new OutputStreamWithJsonWriter(jg);
                        jg.writeFieldName(name);
                        propertyWriter.write(property, Property.class, Property.class, APPLICATION_JSON_TYPE, out);
                    }
                }
            }
        }
    }

    /**
     * @since 8.3
     */
    public static void writeWorkflowVariables(DocumentRoute route, GraphNode node, JsonGenerator jg,
            MarshallerRegistry registry, RenderingContext ctx, SchemaManager schemaManager)
                    throws IOException, JsonGenerationException {
        String facet = (String) route.getDocument().getPropertyValue(GraphRoute.PROP_VARIABLES_FACET);
        if (StringUtils.isNotBlank(facet)) {

            CompositeType type = schemaManager.getFacet(facet);
            if (type != null) {

                final String transientSchemaName = DocumentRoutingConstants.GLOBAL_VAR_SCHEMA_PREFIX + node.getId();
                final Schema transientSchema = schemaManager.getSchema(transientSchemaName);
                if (transientSchema == null) {
                    return;
                }

                boolean hasFacet = route.getDocument().hasFacet(facet);

                Writer<Property> propertyWriter = registry.getWriter(ctx, Property.class, APPLICATION_JSON_TYPE);
                // provides the current route to the property marshaller
                try (Closeable resource = ctx.wrap().with(DocumentModelJsonWriter.ENTITY_TYPE,
                        route.getDocument()).open()) {
                    for (Field f : type.getFields()) {
                        String name = f.getName().getLocalName();
                        if (transientSchema.hasField(name)) {
                            Property property = hasFacet ? route.getDocument().getProperty(name) : null;
                            OutputStream out = new OutputStreamWithJsonWriter(jg);
                            jg.writeFieldName(name);
                            propertyWriter.write(property, Property.class, Property.class, APPLICATION_JSON_TYPE, out);
                        }
                    }
                }
            }
        }
    }
}
