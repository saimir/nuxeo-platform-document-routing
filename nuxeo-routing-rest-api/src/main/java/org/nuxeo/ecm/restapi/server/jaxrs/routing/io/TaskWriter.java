/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     <a href="mailto:grenard@nuxeo.com">Guillaume Renard</a>
 *
 */

package org.nuxeo.ecm.restapi.server.jaxrs.routing.io;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Provider;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.plexus.util.StringUtils;
import org.jboss.el.ExpressionFactoryImpl;
import org.nuxeo.ecm.automation.jaxrs.io.EntityWriter;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.UnrestrictedSessionRunner;
import org.nuxeo.ecm.core.schema.utils.DateParser;
import org.nuxeo.ecm.platform.actions.ActionContext;
import org.nuxeo.ecm.platform.actions.ELActionContext;
import org.nuxeo.ecm.platform.actions.ejb.ActionManager;
import org.nuxeo.ecm.platform.el.ExpressionContext;
import org.nuxeo.ecm.platform.routing.api.DocumentRoutingConstants;
import org.nuxeo.ecm.platform.routing.core.impl.GraphNode;
import org.nuxeo.ecm.platform.routing.core.impl.GraphNode.Button;
import org.nuxeo.ecm.platform.routing.core.impl.GraphRoute;
import org.nuxeo.ecm.platform.task.Task;
import org.nuxeo.ecm.platform.task.TaskComment;
import org.nuxeo.ecm.restapi.server.jaxrs.routing.io.util.JsonEncodeDecodeUtils;
import org.nuxeo.ecm.webengine.jaxrs.session.SessionFactory;
import org.nuxeo.runtime.api.Framework;

/**
 * @since 7.2
 */
@Provider
@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON + "+nxentity" })
public class TaskWriter extends EntityWriter<Task> {

    public static final String ENTITY_TYPE = "task";

    @Context
    HttpServletRequest request;

    @Context
    UriInfo uriInfo;

    @Override
    protected void writeEntityBody(JsonGenerator jg, Task item) throws IOException, ClientException {
        writeTask(jg, item, request, uriInfo);
    }

    public static void writeTask(JsonGenerator jg, Task item, HttpServletRequest request, UriInfo uriInfo)
            throws JsonGenerationException, IOException {
        CoreSession session = null;
        GraphRoute workflowInstance = null;
        GraphNode node = null;
        String workflowInstanceId = item.getProcessId();
        final String nodeId = item.getVariable(DocumentRoutingConstants.TASK_NODE_ID_KEY);
        if (request != null) {
            session = SessionFactory.getSession(request);
        }
        if (session != null && StringUtils.isNotBlank(workflowInstanceId)) {
            NodeAccesRunner nodeAccesRunner = new NodeAccesRunner(session, workflowInstanceId, nodeId);
            nodeAccesRunner.runUnrestricted();
            workflowInstance = nodeAccesRunner.workflowInstance;
            node = nodeAccesRunner.node;
        }

        jg.writeStringField("id", item.getDocument().getId());
        jg.writeStringField("name", item.getName());
        jg.writeStringField("workflowInstanceId", workflowInstanceId);
        if (workflowInstance != null) {
            final String workflowModelId = workflowInstance.getModelId();
            if (StringUtils.isNotBlank(workflowModelId)) {
                GraphRoute model = null;
                String workflowModelName = null;
                model = session.getDocument(new IdRef(workflowModelId)).getAdapter(GraphRoute.class);
                workflowModelName = model.getName();
                jg.writeStringField("workflowModelName", workflowModelName);
            }
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
        for (Entry<String, Serializable> e : node.getVariables().entrySet()) {
            JsonEncodeDecodeUtils.encodeVariableEntry(e, jg, request);
        }
        // add workflow variables
        if (workflowInstance != null) {
            // TODO, do we really want to let all users see global variables?
            for (Entry<String, Serializable> e : workflowInstance.getVariables().entrySet()) {
                JsonEncodeDecodeUtils.encodeVariableEntry(e, jg, request);
            }
        }
        jg.writeEndObject();

        if (session != null) {
            jg.writeFieldName("taskInfo");
            jg.writeStartObject();
            final ActionManager actionManager = Framework.getService(ActionManager.class);
            jg.writeArrayFieldStart("taskActions");
            for (Button button : node.getTaskButtons()) {
                if (StringUtils.isBlank(button.getFilter())
                        || actionManager.checkFilter(button.getFilter(), createActionContext(session))) {
                    jg.writeStartObject();
                    jg.writeStringField("name", button.getName());
                    jg.writeStringField("url", uriInfo.getBaseUri() + "api/v1/task" + item.getDocument().getId() + "/"
                            + button.getName());
                    jg.writeStringField("label", button.getLabel());
                    jg.writeEndObject();
                }
            }
            jg.writeEndArray();

            jg.writeFieldName("layoutResource");
            jg.writeStartObject();
            jg.writeStringField("name", node.getTaskLayout());
            jg.writeStringField("url",
                    uriInfo.getBaseUri() + "/layout-manager/layouts/?layoutName=" + node.getTaskLayout());
            jg.writeEndObject();

            jg.writeArrayFieldStart("schemas");
            for (String schema : node.getDocument().getSchemas()) {
                // TODO only keep functional schema once adaptation done
                jg.writeStartObject();
                jg.writeStringField("name", schema);
                jg.writeStringField("url", uriInfo.getBaseUri() + "api/v1/config/schemas/" + schema);
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

    @Override
    protected String getEntityType() {
        return ENTITY_TYPE;
    }

    protected static void writeVariableEntry(Entry<String, Serializable> e, JsonGenerator jg, HttpServletRequest request)
            throws JsonGenerationException, IOException {
        if (e.getValue() instanceof Blob) {
            jg.writeFieldName(e.getKey());
            JsonEncodeDecodeUtils.encodeBlob((Blob) e.getValue(), jg, request);
        } else {
            jg.writeObjectField(e.getKey(), e.getValue());
        }
    }


    protected static class NodeAccesRunner extends UnrestrictedSessionRunner {

        GraphNode node;

        GraphRoute workflowInstance;

        String workflowInstanceId;

        String nodeId;

        protected NodeAccesRunner(CoreSession session, String workflowInstanceId, String nodeId) {
            super(session);
            this.workflowInstanceId = workflowInstanceId;
            this.nodeId = nodeId;
        }

        @Override
        public void run() throws ClientException {
            DocumentModel workflowInstanceDoc = session.getDocument(new IdRef(workflowInstanceId));
            workflowInstance = workflowInstanceDoc.getAdapter(GraphRoute.class);
            node = workflowInstance.getNode(nodeId);
        }

    }
}
