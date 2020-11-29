/**
 * Copyright © 2016-2020 The Thingsboard Authors
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
 */
package org.thingsboard.server.transport.lwm2m.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.core.node.*;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.request.*;
import org.eclipse.leshan.core.response.*;
import org.eclipse.leshan.core.util.Hex;
import org.eclipse.leshan.server.californium.LeshanServer;
import org.eclipse.leshan.server.registration.Registration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.common.transport.adaptor.AdaptorException;
import org.thingsboard.server.common.transport.service.DefaultTransportService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.ToTransportUpdateCredentialsProto;
import org.thingsboard.server.gen.transport.TransportProtos.SessionEvent;
import org.thingsboard.server.gen.transport.TransportProtos.SessionInfoProto;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceCredentialsResponseMsg;
import org.thingsboard.server.gen.transport.TransportProtos.PostTelemetryMsg;
import org.thingsboard.server.gen.transport.TransportProtos.PostAttributeMsg;
import org.thingsboard.server.transport.lwm2m.server.client.AttrTelemetryObserveValue;
import org.thingsboard.server.transport.lwm2m.server.adaptors.LwM2MJsonAdaptor;
import org.thingsboard.server.transport.lwm2m.server.client.ModelClient;
import org.thingsboard.server.transport.lwm2m.server.client.ModelObject;
import org.thingsboard.server.transport.lwm2m.server.client.ResultsAnalyzerParameters;
import org.thingsboard.server.transport.lwm2m.server.secure.LwM2mInMemorySecurityStore;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.*;
import static org.eclipse.leshan.core.model.ResourceModel.Type.OPAQUE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2MTransportHandler.*;

@Slf4j
@Service("LwM2MTransportService")
@ConditionalOnExpression("('${service.type:null}'=='tb-transport' && '${transport.lwm2m.enabled:false}'=='true' ) || ('${service.type:null}'=='monolith' && '${transport.lwm2m.enabled}'=='true')")
public class LwM2MTransportService {

    @Autowired
    private LwM2MJsonAdaptor adaptor;

    @Autowired
    private TransportService transportService;

    @Autowired
    public LwM2MTransportContextServer context;

    @Autowired
    private LwM2MTransportRequest lwM2MTransportRequest;

    @Autowired
    LwM2mInMemorySecurityStore lwM2mInMemorySecurityStore;


    @PostConstruct
    public void init() {
        context.getScheduler().scheduleAtFixedRate(() -> checkInactivityAndReportActivity(), new Random().nextInt((int) context.getCtxServer().getSessionReportTimeout()), context.getCtxServer().getSessionReportTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * Start registration device
     * Create session: Map<String <registrationId >, ModelClient>
     * 1. replaceNewRegistration -> (solving the problem of incorrect termination of the previous session with this endpoint)
     * 1.1 When we initialize the registration, we register the session by endpoint.
     * 1.2 If the server has incomplete requests (canceling the registration of the previous session),
     * delete the previous session only by the previous registration.getId
     * 1.2 Add Model (Entity) for client (from registration & observe) by registration.getId
     * 1.2 Remove from sessions Model by enpPoint
     * Next ->  Create new ModelClient for current session -> setModelClient...
     *
     * @param lwServer             - LeshanServer
     * @param registration         - Registration LwM2M Client
     * @param previousObsersations - may be null
     */
    public void onRegistered(LeshanServer lwServer, Registration registration, Collection<Observation> previousObsersations) {
        ModelClient modelClient = lwM2mInMemorySecurityStore.getModel(lwServer, registration, this);
        if (modelClient != null) {
            modelClient.setLwM2MTransportService(this);
            modelClient.setSessionUuid(UUID.randomUUID());
            this.setModelClient(lwServer, registration, modelClient);
            log.warn("[{}] endpoint [{}] UUID ValidateSessionInfo", modelClient.getEndPoint(), modelClient.getSessionUuid());
            SessionInfoProto sessionInfo = this.getValidateSessionInfo(registration.getId());
            if (sessionInfo != null) {
                log.info("Client: [{}] onRegistered [{}] name  [{}] profile, [{}] SessionIdMSB, [{}] SessionIdLSB, [{}] session UUID", registration.getId(), registration.getEndpoint(), sessionInfo.getDeviceType(), sessionInfo.getSessionIdMSB(), sessionInfo.getSessionIdLSB(), LwM2MTransportHandler.toSessionId(sessionInfo));
                transportService.registerAsyncSession(sessionInfo, new LwM2MSessionMsgListener(this));
                transportService.process(sessionInfo, DefaultTransportService.getSessionEventMsg(SessionEvent.OPEN), null);
            } else {
                log.error("Client: [{}] onRegistered [{}] name  [{}] sessionInfo ", registration.getId(), registration.getEndpoint(), sessionInfo);
            }
        } else {
            log.error("Client: [{}] onRegistered [{}] name  [{}] modelClient ", registration.getId(), registration.getEndpoint(), modelClient);
        }
    }

    /**
     * @param lwServer     - LeshanServer
     * @param registration - Registration LwM2M Client
     */
    public void updatedReg(LeshanServer lwServer, Registration registration) {
        SessionInfoProto sessionInfo = this.getValidateSessionInfo(registration.getId());
        if (sessionInfo != null) {
            log.info("Client: [{}] updatedReg [{}] name  [{}] profile ", registration.getId(), registration.getEndpoint(), sessionInfo.getDeviceType());
        } else {
            log.error("Client: [{}] updatedReg [{}] name  [{}] sessionInfo ", registration.getId(), registration.getEndpoint(), sessionInfo);
        }
    }

    /**
     * @param registration - Registration LwM2M Client
     * @param observations - All paths observations before unReg
     *                     !!! Warn: if have not finishing unReg, then this operation will be finished on next Client`s connect
     */
    public void unReg(Registration registration, Collection<Observation> observations) {
        SessionInfoProto sessionInfo = this.getValidateSessionInfo(registration.getId());
        if (sessionInfo != null) {
            transportService.deregisterSession(sessionInfo);
            this.doCloseSession(sessionInfo);
            lwM2mInMemorySecurityStore.addRemoveSessions(registration.getId());
            lwM2mInMemorySecurityStore.delRemoveSessions();
            if (lwM2mInMemorySecurityStore.getProfiles().size() > 0) {
                this.updateProfiles();
            }
            log.info("Client: [{}] unReg [{}] name  [{}] profile ", registration.getId(), registration.getEndpoint(), sessionInfo.getDeviceType());
        } else {
            log.error("Client: [{}] unReg [{}] name  [{}] sessionInfo ", registration.getId(), registration.getEndpoint(), sessionInfo);
        }
    }

    private void updateProfiles() {
        if (lwM2mInMemorySecurityStore.getSessions().size() == 0)
            lwM2mInMemorySecurityStore.setProfiles(new ConcurrentHashMap<>());
        else {
            Map<UUID, AttrTelemetryObserveValue> profilesClone = lwM2mInMemorySecurityStore.getProfiles().entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            Map.Entry::getValue));
            profilesClone.forEach((k, v) -> {
                log.warn("[{}] UUId, [{}] sessions", k, lwM2mInMemorySecurityStore.getSessions());
            });
        }
    }

    public void onSleepingDev(Registration registration) {
        String endpointId = registration.getEndpoint();
        String lwm2mVersion = registration.getLwM2mVersion();
        log.info("[{}] [{}] Received endpoint Sleeping version event", endpointId, lwm2mVersion);
        //TODO: associate endpointId with device information.
    }

    public void onAwakeDev(Registration registration) {
        String endpointId = registration.getEndpoint();
        String lwm2mVersion = registration.getLwM2mVersion();
        log.info("[{}] [{}] Received endpoint Awake version event", endpointId, lwm2mVersion);
        //TODO: associate endpointId with device information.
    }

    /**
     * Create new ModelClient for current session -> setModelClient...
     * #1   Add all ObjectLinks (instance) to control the process of executing requests to the client
     * to get the client model with current values
     * #2   Get the client model with current values. Analyze the response in -> lwM2MTransportRequest.sendResponse
     *
     * @param lwServer     - LeshanServer
     * @param registration - Registration LwM2M Client
     * @param modelClient  - object with All parameters off client
     */
    private void setModelClient(LeshanServer lwServer, Registration registration, ModelClient modelClient) {
        // #1
        Arrays.stream(registration.getObjectLinks()).forEach(url -> {
            ResultIds pathIds = new ResultIds(url.getUrl());
            if (pathIds.instanceId > -1 && pathIds.resourceId == -1) {
                modelClient.addPendingRequests(url.getUrl());
            }
        });
        // #2
        Arrays.stream(registration.getObjectLinks()).forEach(url -> {
            ResultIds pathIds = new ResultIds(url.getUrl());
            if (pathIds.instanceId > -1 && pathIds.resourceId == -1) {
                lwM2MTransportRequest.sendAllRequest(lwServer, registration, url.getUrl(), GET_TYPE_OPER_READ,
                        ContentFormat.TLV.getName(), modelClient, null, "", this.context.getCtxServer().getTimeout());
            }
        });
    }

    /**
     * @param registrationId - Id of Registration LwM2M Client
     * @return - sessionInfo after access connect client
     */
    private SessionInfoProto getValidateSessionInfo(String registrationId) {
        SessionInfoProto sessionInfo = null;
        ModelClient modelClient = lwM2mInMemorySecurityStore.getByRegistrationIdModelClient(registrationId);
        if (modelClient != null) {
            ValidateDeviceCredentialsResponseMsg msg = modelClient.getCredentialsResponse();
            if (msg == null || msg.getDeviceInfo() == null) {
                log.warn("[{}] [{}]", modelClient.getEndPoint(), CONNECTION_REFUSED_NOT_AUTHORIZED.toString());
            } else {
                sessionInfo = SessionInfoProto.newBuilder()
                        .setNodeId(this.context.getNodeId())
                        .setSessionIdMSB(modelClient.getSessionUuid().getMostSignificantBits())
                        .setSessionIdLSB(modelClient.getSessionUuid().getLeastSignificantBits())
                        .setDeviceIdMSB(msg.getDeviceInfo().getDeviceIdMSB())
                        .setDeviceIdLSB(msg.getDeviceInfo().getDeviceIdLSB())
                        .setTenantIdMSB(msg.getDeviceInfo().getTenantIdMSB())
                        .setTenantIdLSB(msg.getDeviceInfo().getTenantIdLSB())
                        .setDeviceName(msg.getDeviceInfo().getDeviceName())
                        .setDeviceType(msg.getDeviceInfo().getDeviceType())
                        .setDeviceProfileIdLSB(msg.getDeviceInfo().getDeviceProfileIdLSB())
                        .setDeviceProfileIdMSB(msg.getDeviceInfo().getDeviceProfileIdMSB())
                        .build();
            }
        }
        return sessionInfo;
    }

    /**
     * Add attribute/telemetry information from Client and credentials/Profile to client model and start observe
     * !!! if the resource has an observation, but no telemetry or attribute - the observation will not use
     * #1 Client`s starting info  to  send to thingsboard
     * #2 Sending Attribute Telemetry with value to thingsboard only once at the start of the connection
     * #3 Start observe
     *
     * @param lwServer     - LeshanServer
     * @param registration - Registration LwM2M Client
     * @param modelClient  - object with All parameters off client
     */
    @SneakyThrows
    public void updatesAndSentModelParameter(LeshanServer lwServer, Registration registration, ModelClient modelClient, DeviceProfile deviceProfile) {
        // #1
//        this.setParametersToModelClient(registration, modelClient, deviceProfile);
        // #2
        this.updateAttrTelemetry(registration, true, null);
        // #3
        this.onSentObserveToClient(lwServer, registration);
    }


    /**
     * Sent Attribute and Telemetry to Thingsboard
     * #1 - get AttrName/TelemetryName with value:
     * #1.1 from Client
     * #1.2 from ModelClient:
     * -- resourceId == path from AttrTelemetryObserveValue.postAttributeProfile/postTelemetryProfile/postObserveProfile
     * -- AttrName/TelemetryName == resourceName from ModelObject.objectModel, value from ModelObject.instance.resource(resourceId)
     * #2 - set Attribute/Telemetry
     *
     * @param registration - Registration LwM2M Client
     */
    private void updateAttrTelemetry(Registration registration, boolean start, String path) {
        JsonObject attributes = new JsonObject();
        JsonObject telemetry = new JsonObject();
        if (start) {
            // #1.1
            JsonObject attributeClient = getAttributeClient(registration);
            if (attributeClient != null) {
                attributeClient.entrySet().forEach(p -> {
                    attributes.add(p.getKey(), p.getValue());
                });
            }
        }
        // #1.2
        CountDownLatch cancelLatch = new CountDownLatch(1);
        this.getParametersFromModelClient(attributes, telemetry, registration, path);
        cancelLatch.countDown();
        try {
            cancelLatch.await(DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
        }
        if (attributes.getAsJsonObject().entrySet().size() > 0)
            this.updateParametersOnThingsboard(attributes, DEVICE_ATTRIBUTES_TOPIC, registration.getId());
        if (telemetry.getAsJsonObject().entrySet().size() > 0)
            this.updateParametersOnThingsboard(telemetry, DEVICE_TELEMETRY_TOPIC, registration.getId());
    }

    /**
     * get AttrName/TelemetryName with value from Client
     *
     * @param registration
     * @return - JsonObject, format: {name: value}}
     */
    private JsonObject getAttributeClient(Registration registration) {
        if (registration.getAdditionalRegistrationAttributes().size() > 0) {
            JsonObject resNameValues = new JsonObject();
            registration.getAdditionalRegistrationAttributes().entrySet().forEach(entry -> {
                resNameValues.addProperty(entry.getKey(), entry.getValue());
            });
            return resNameValues;
        }
        return null;
    }

    /**
     * @param attributes   - new JsonObject
     * @param telemetry    - new JsonObject
     * @param registration - Registration LwM2M Client
     *                     result: add to JsonObject those resources to which the user is subscribed and they have a value
     *                     (attributes/telemetry): new {name(Attr/Telemetry):value}
     */
    private void getParametersFromModelClient(JsonObject attributes, JsonObject telemetry, Registration registration, String path) {
        UUID profileUUid = lwM2mInMemorySecurityStore.getSessions().get(registration.getId()).getProfileUuid();
        AttrTelemetryObserveValue attrTelemetryObserveValue = lwM2mInMemorySecurityStore.getProfiles().get(profileUUid);
        attrTelemetryObserveValue.getPostAttributeProfile().forEach(p -> {
            ResultIds pathIds = new ResultIds(p.getAsString().toString());
            if (pathIds.getResourceId() > -1) {
                if (path == null || path.equals(p.getAsString().toString())) {
                    this.addParameters(pathIds, p.getAsString().toString(), attributes, registration);
                }
            }
        });
        attrTelemetryObserveValue.getPostTelemetryProfile().forEach(p -> {
            ResultIds pathIds = new ResultIds(p.getAsString().toString());
            if (pathIds.getResourceId() > -1) {
                if (path == null || path.equals(p.getAsString().toString())) {
                    this.addParameters(pathIds, p.getAsString().toString(), telemetry, registration);
                }
            }
        });
    }

    /**
     * @param pathIds      - path resource
     * @param parameters   - JsonObject attributes/telemetry
     * @param registration - Registration LwM2M Client
     */
    private void addParameters(ResultIds pathIds, String path, JsonObject parameters, Registration registration) {
        ModelObject modelObject = lwM2mInMemorySecurityStore.getSessions().get(registration.getId()).getModelObjects().get(pathIds.getObjectId());
        UUID profileUUid = lwM2mInMemorySecurityStore.getSessions().get(registration.getId()).getProfileUuid();
        JsonObject names = lwM2mInMemorySecurityStore.getProfiles().get(profileUUid).getPostKeyNameProfile();
        String resName = String.valueOf(names.get(path));
        if (modelObject != null && resName != null && !resName.isEmpty()) {
            String resValue = this.getResourceValue(modelObject, pathIds);
            if (resValue != null) {
//                String resName = modelObject.getObjectModel().resources.get(pathIds.getResourceId()).name;
                parameters.addProperty(resName, resValue);
            }
        }
    }

    /**
     * @param modelObject - ModelObject of Client
     * @param pathIds     - path resource
     * @return - value of Resource or null
     */
    private String getResourceValue(ModelObject modelObject, ResultIds pathIds) {
        String resValue = null;
        if (modelObject.getInstances().get(pathIds.getInstanceId()) != null) {
            LwM2mObjectInstance instance = modelObject.getInstances().get(pathIds.getInstanceId());
            if (instance.getResource(pathIds.getResourceId()) != null) {
                resValue = instance.getResource(pathIds.getResourceId()).getType() == OPAQUE ?
                        Hex.encodeHexString((byte[]) instance.getResource(pathIds.getResourceId()).getValue()).toLowerCase() :
                        (instance.getResource(pathIds.getResourceId()).isMultiInstances()) ?
                                instance.getResource(pathIds.getResourceId()).getValues().toString() :
                                instance.getResource(pathIds.getResourceId()).getValue().toString();
            }
        }
        return resValue;
    }

    /**
     * Prepare Sent to Thigsboard callback - Attribute or Telemetry
     *
     * @param msg            - JsonArray: [{name: value}]
     * @param topicName      - Api Attribute or Telemetry
     * @param registrationId - Id of Registration LwM2M Client
     */
    public void updateParametersOnThingsboard(JsonElement msg, String topicName, String registrationId) {
        SessionInfoProto sessionInfo = this.getValidateSessionInfo(registrationId);
        if (sessionInfo != null) {
            try {
                if (topicName.equals(LwM2MTransportHandler.DEVICE_ATTRIBUTES_TOPIC)) {
                    PostAttributeMsg postAttributeMsg = adaptor.convertToPostAttributes(msg);
                    TransportServiceCallback call = this.getPubAckCallbackSentAttrTelemetry(-1, postAttributeMsg);
                    transportService.process(sessionInfo, postAttributeMsg, call);
                } else if (topicName.equals(LwM2MTransportHandler.DEVICE_TELEMETRY_TOPIC)) {
                    PostTelemetryMsg postTelemetryMsg = adaptor.convertToPostTelemetry(msg);
                    TransportServiceCallback call = this.getPubAckCallbackSentAttrTelemetry(-1, postTelemetryMsg);
                    transportService.process(sessionInfo, postTelemetryMsg, this.getPubAckCallbackSentAttrTelemetry(-1, call));
                }
            } catch (AdaptorException e) {
                log.error("[{}] Failed to process publish msg [{}]", topicName, e);
                log.info("[{}] Closing current session due to invalid publish", topicName);
            }
        } else {
            log.error("Client: [{}] updateParametersOnThingsboard [{}] sessionInfo ", registrationId, sessionInfo);
        }
    }

    /**
     * Sent to Thingsboard Attribute || Telemetry
     *
     * @param msgId - always == -1
     * @param msg   - JsonObject: [{name: value}]
     * @return - dummy
     */
    private <T> TransportServiceCallback<Void> getPubAckCallbackSentAttrTelemetry(final int msgId, final T msg) {
        return new TransportServiceCallback<Void>() {
            @Override
            public void onSuccess(Void dummy) {
                log.trace("Success to publish msg: {}, dummy: {}", msg, dummy);
            }

            @Override
            public void onError(Throwable e) {
                log.trace("[{}] Failed to publish msg: {}", msg, e);
            }
        };
    }

    /**
     * Start observe
     * #1 - Analyze:
     * #1.1 path in observe == (attribute or telemetry)
     * #1.2 recourseValue notNull
     * #2 Analyze after sent request (response):
     * #2.1 First: lwM2MTransportRequest.sendResponse -> ObservationListener.newObservation
     * #2.2 Next: ObservationListener.onResponse     *
     *
     * @param lwServer     - LeshanServer
     * @param registration - Registration LwM2M Client
     */
    private void onSentObserveToClient(LeshanServer lwServer, Registration registration) {
        if (lwServer.getObservationService().getObservations(registration).size() > 0) {
            this.setCancelObservations(lwServer, registration);
        }
        UUID profileUUid = lwM2mInMemorySecurityStore.getSessions().get(registration.getId()).getProfileUuid();
        AttrTelemetryObserveValue attrTelemetryObserveValue = lwM2mInMemorySecurityStore.getProfiles().get(profileUUid);
        attrTelemetryObserveValue.getPostObserveProfile().forEach(p -> {
            // #1.1
            String target = (getValidateObserve(attrTelemetryObserveValue.getPostAttributeProfile(), p.getAsString().toString())) ?
                    p.getAsString().toString() : (getValidateObserve(attrTelemetryObserveValue.getPostTelemetryProfile(), p.getAsString().toString())) ?
                    p.getAsString().toString() : null;
            if (target != null) {
                // #1.2
                ResultIds pathIds = new ResultIds(target);
                ModelObject modelObject = lwM2mInMemorySecurityStore.getSessions().get(registration.getId()).getModelObjects().get(pathIds.getObjectId());
                // #2
                if (modelObject != null) {
                    if (getResourceValue(modelObject, pathIds) != null) {
                        lwM2MTransportRequest.sendAllRequest(lwServer, registration, target, GET_TYPE_OPER_OBSERVE,
                                null, null, null, "", this.context.getCtxServer().getTimeout());
                    }
                }
            }
        });
    }

    public void setCancelObservations(LeshanServer lwServer, Registration registration) {
        log.info("33)  setCancelObservationObjects endpoint: {}", registration.getEndpoint());
        if (registration != null) {
            Set<Observation> observations = lwServer.getObservationService().getObservations(registration);
            observations.forEach(observation -> {
                log.info("33_1)  setCancelObservationObjects endpoint: {} cancel: {}", registration.getEndpoint());
                this.setCancelObservationRecourse(lwServer, registration, observation.getPath().toString());
            });
        }
    }

    /**
     * lwM2MTransportRequest.sendAllRequest(lwServer, registration, path, POST_TYPE_OPER_OBSERVE_CANCEL, null, null, null, null, context.getTimeout());
     * At server side this will not remove the observation from the observation store, to do it you need to use
     * {@code ObservationService#cancelObservation()}
     */
    public void setCancelObservationRecourse(LeshanServer lwServer, Registration registration, String path) {
        CountDownLatch cancelLatch = new CountDownLatch(1);
        lwServer.getObservationService().cancelObservations(registration, path);
        cancelLatch.countDown();
        try {
            cancelLatch.await(DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
        }
    }

    /**
     * @param parameters - JsonArray postAttributeProfile/postTelemetryProfile
     * @param path       - recourse from postObserveProfile
     * @return rez - true if path observe is in attribute/telemetry
     */
    private boolean getValidateObserve(JsonElement parameters, String path) {
        AtomicBoolean rez = new AtomicBoolean(false);
        if (parameters.isJsonArray()) {
            parameters.getAsJsonArray().forEach(p -> {
                        if (p.getAsString().toString().equals(path)) rez.set(true);
                    }
            );
        } else if (parameters.isJsonObject()) {
            rez.set((parameters.getAsJsonObject().entrySet()).stream().map(json -> json.toString())
                    .filter(path::equals).findAny().orElse(null) != null);
        }
        return rez.get();
    }

    /**
     * Sending observe value to thingsboard from ObservationListener.onResponse: object, instance, SingleResource or MultipleResource
     *
     * @param registration - Registration LwM2M Client
     * @param path         - observe
     * @param response     - observe
     */
    @SneakyThrows
    public void onObservationResponse(Registration registration, String path, ReadResponse response) {
        if (response.getContent() instanceof LwM2mObject) {
            LwM2mObject content = (LwM2mObject) response.getContent();
            String target = "/" + content.getId();
            log.info("observOnResponse Object: \n target {}", target);
        } else if (response.getContent() instanceof LwM2mObjectInstance) {
            LwM2mObjectInstance content = (LwM2mObjectInstance) response.getContent();
            log.info("[{}] \n observOnResponse instance: {}", registration.getEndpoint(), content.getId());
        } else if (response.getContent() instanceof LwM2mSingleResource) {
            LwM2mSingleResource content = (LwM2mSingleResource) response.getContent();
            this.onObservationSetResourcesValue(registration, content.getValue(), null, path);
        } else if (response.getContent() instanceof LwM2mMultipleResource) {
            LwM2mSingleResource content = (LwM2mSingleResource) response.getContent();
            this.onObservationSetResourcesValue(registration, null, content.getValues(), path);
        }
    }


    /**
     * Sending observe value of resources to thingsboard
     * #1 Return old Resource from ModelObject
     * #2 Create new Resource with value from observation
     * #3 Create new Resources from old Resources
     * #4 Update new Resources (replace old Resource on new Resource)
     * #5 Remove old Instance from modelClient
     * #6 Create new Instance with new Resources values
     * #7 Update modelClient.getModelObjects(idObject) (replace old Instance on new Instance)
     *
     * @param registration - Registration LwM2M Client
     * @param value        - LwM2mSingleResource response.getContent()
     * @param values       - LwM2mSingleResource response.getContent()
     * @param path         - resource
     */
    @SneakyThrows
    private void onObservationSetResourcesValue(Registration registration, Object value, Map<Integer, ?> values, String path) {

        ResultIds resultIds = new ResultIds(path);
        // #1
        ModelClient modelClient = lwM2mInMemorySecurityStore.getByRegistrationIdModelClient(registration.getId());
        ModelObject modelObject = modelClient.getModelObjects().get(resultIds.getObjectId());
        Map<Integer, LwM2mObjectInstance> instancesModelObject = modelObject.getInstances();
        LwM2mObjectInstance instanceOld = (instancesModelObject.get(resultIds.instanceId) != null) ? instancesModelObject.get(resultIds.instanceId) : null;
        Map<Integer, LwM2mResource> resourcesOld = (instanceOld != null) ? instanceOld.getResources() : null;
        LwM2mResource resourceOld = (resourcesOld != null && resourcesOld.get(resultIds.getResourceId()) != null) ? resourcesOld.get(resultIds.getResourceId()) : null;
        // #2
        LwM2mResource resourceNew;
        if (resourceOld.isMultiInstances()) {
            resourceNew = LwM2mMultipleResource.newResource(resultIds.getResourceId(), values, resourceOld.getType());
        } else {
            resourceNew = LwM2mSingleResource.newResource(resultIds.getResourceId(), value, resourceOld.getType());
        }
        //#3
        Map<Integer, LwM2mResource> resourcesNew = new HashMap<>(resourcesOld);
        // #4
        resourcesNew.remove(resourceOld);
        // #5
        resourcesNew.put(resultIds.getResourceId(), resourceNew);
        // #6
        LwM2mObjectInstance instanceNew = new LwM2mObjectInstance(resultIds.instanceId, resourcesNew.values());
        // #7
        CountDownLatch respLatch = new CountDownLatch(1);
        modelClient.getModelObjects().get(resultIds.getObjectId()).removeInstance(resultIds.instanceId);
        instancesModelObject.put(resultIds.instanceId, instanceNew);
        respLatch.countDown();
        try {
            respLatch.await(DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
        }
        this.updateAttrTelemetry(registration, false, path);
    }

    /**
     * Get info about changeCredentials from Thingsboard
     * #1 Equivalence test: old <> new Value (Only Attribute, Telemetry, Observe)
     * #1.1 Attribute
     * #1.2 Telemetry
     * #1.3 Observe
     * #2 If #1 == change, then analyze and update Value in Transport
     * #2.1 Attribute.add:
     * -- if is value in modelClient: add modelClient.getAttrTelemetryObserveValue().getPostAttribute and Get Thingsboard (Attr)
     * -- if is not value in modelClient: nothing
     * #2.2 Telemetry.add:
     * -- if is value in modelClient: add modelClient.getAttrTelemetryObserveValue().getPostTelemetry and Get Thingsboard (Telemetry)
     * -- if is not value in modelClient: nothing
     * #2.3 Observe.add
     * --- if path is in Attr/Telemetry and value not null: =>add to modelClient.getAttrTelemetryObserveValue().getPostObserve() and Request observe to Client
     * # 2.4 del (Attribute, Telemetry)
     * --  #2.5.
     * #2.5 Observe.del
     * -- if value not null: Request cancel observe to client
     * #3 Update in modelClient.getAttrTelemetryObserveValue(): ...Profile
     *
     * @param updateCredentials - Credentials include info about Attr/Telemetry/Observe (Profile)
     */

    public void onToTransportUpdateCredentials(ToTransportUpdateCredentialsProto updateCredentials) {
//        String credentialsId = (updateCredentials.getCredentialsIdCount() > 0) ? updateCredentials.getCredentialsId(0) : null;
//        JsonObject credentialsValue = (updateCredentials.getCredentialsValueCount() > 0) ? LwM2MTransportHandler.validateJson(updateCredentials.getCredentialsValue(0)) : null;
//        if (credentialsValue != null && !credentialsValue.isJsonNull() && credentialsId != null && !credentialsId.isEmpty()) {
//            String registrationId = lwM2mInMemorySecurityStore.getByRegistrationId(credentialsId);
//            Registration registration = lwM2mInMemorySecurityStore.getByRegistration(registrationId);
//            ModelClient modelClient = lwM2mInMemorySecurityStore.getByRegistrationIdModelClient(registrationId);
//            LeshanServer lwServer = modelClient.getLwServer();
//            log.info("updateCredentials -> registration: {}", registration);
//
//            // #1
//            JsonObject observeAttrNewJson = (credentialsValue.has("observeAttr") && !credentialsValue.get("observeAttr").isJsonNull()) ? credentialsValue.get("observeAttr").getAsJsonObject() : null;
//            log.info("updateCredentials -> observeAttr: {}", observeAttrNewJson);
//            JsonArray attributeNew = (observeAttrNewJson.has("attribute") && !observeAttrNewJson.get("attribute").isJsonNull()) ? observeAttrNewJson.get("attribute").getAsJsonArray() : null;
//            JsonArray telemetryNew = (observeAttrNewJson.has("telemetry") && !observeAttrNewJson.get("telemetry").isJsonNull()) ? observeAttrNewJson.get("telemetry").getAsJsonArray() : null;
//            JsonArray observeNew = (observeAttrNewJson.has("observe") && !observeAttrNewJson.get("observe").isJsonNull()) ? observeAttrNewJson.get("observe").getAsJsonArray() : null;
//            if (!modelClient.getAttrTelemetryObserveValue().getPostAttributeProfile().equals(attributeNew) ||
//                    !modelClient.getAttrTelemetryObserveValue().getPostTelemetryProfile().equals(telemetryNew) ||
//                    !modelClient.getAttrTelemetryObserveValue().getPostObserveProfile().equals(observeNew)) {
//                if (!modelClient.getAttrTelemetryObserveValue().getPostAttributeProfile().equals(attributeNew) ||
//                        !modelClient.getAttrTelemetryObserveValue().getPostTelemetryProfile().equals(telemetryNew)) {
//                    // #1.1
//                    ResultsAnalyzerParameters postAttributeAnalyzer = getAnalyzerParameters(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostAttributeProfile(), Set.class),
//                            new Gson().fromJson(attributeNew, Set.class));
//                    // #1.2
//                    ResultsAnalyzerParameters postTelemetryAnalyzer = getAnalyzerParameters(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostTelemetryProfile(), Set.class),
//                            new Gson().fromJson(telemetryNew, Set.class));
//
//                    // #2 add
//                    // #2.1 add
//                    ResultsAnalyzerParameters postAttrTelemetryOldAnalyzer = null;
//                    if (postAttributeAnalyzer != null && postAttributeAnalyzer.getPathPostParametersAdd().size() > 0) {
//                        // analyze new Attr with old Telemetry
//                        postAttrTelemetryOldAnalyzer = getAnalyzerParameters(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostTelemetryProfile(), Set.class),
//                                postAttributeAnalyzer.getPathPostParametersAdd());
//                        // sent GET_TYPE_OPER_READ if need
//                        ResultsAnalyzerParameters sentAttrToThingsboard = null;
//                        if (postAttrTelemetryOldAnalyzer != null && postAttrTelemetryOldAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            this.updateResourceValueObserve(lwServer, registration, modelClient, postAttrTelemetryOldAnalyzer.getPathPostParametersAdd(), GET_TYPE_OPER_READ);
//                            // prepare sent attr to tingsboard only not GET_TYPE_OPER_READ
//                            sentAttrToThingsboard = getAnalyzerParameters(postAttributeAnalyzer.getPathPostParametersAdd(),
//                                    postAttrTelemetryOldAnalyzer.getPathPostParametersAdd());
//                        } else {
//                            sentAttrToThingsboard = new ResultsAnalyzerParameters();
//                            sentAttrToThingsboard.getPathPostParametersAdd().addAll(new Gson().fromJson(attributeNew, Set.class));
//                        }
//                        // sent attr to tingsboard only not GET_TYPE_OPER_READ
//                        if (sentAttrToThingsboard != null && sentAttrToThingsboard.getPathPostParametersAdd().size() > 0) {
//                            sentAttrToThingsboard.getPathPostParametersAdd().forEach(p -> {
//                                updateAttrTelemetry(registration, false, p);
//                            });
//
//                        }
//                        // analyze on observe
//                        ResultsAnalyzerParameters postObserveNewAttrAnalyzer = getAnalyzerParametersIn(new Gson().fromJson(observeNew, Set.class),
//                                postAttributeAnalyzer.getPathPostParametersAdd());
//                        if (postObserveNewAttrAnalyzer != null && postObserveNewAttrAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            this.updateResourceValueObserve(lwServer, registration, modelClient, postObserveNewAttrAnalyzer.getPathPostParametersAdd(), GET_TYPE_OPER_OBSERVE);
//                        }
//                    }
//                    // #2.2 add
//                    if (postTelemetryAnalyzer != null && postTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                        // analyze new Telemetry with old Attribute
//                        ResultsAnalyzerParameters postAttrOldTelemetryAnalyzer = getAnalyzerParameters(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostAttributeProfile(), Set.class),
//                                postTelemetryAnalyzer.getPathPostParametersAdd());
//                        // analyze new Telemetry with new Attribute
//                        ResultsAnalyzerParameters postAttrNewTelemetryAnalyzer = null;
//                        if (postAttrTelemetryOldAnalyzer != null && postAttrTelemetryOldAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            postAttrNewTelemetryAnalyzer = getAnalyzerParameters(postAttrTelemetryOldAnalyzer.getPathPostParametersAdd(),
//                                    postTelemetryAnalyzer.getPathPostParametersAdd());
//                        }
//                        if (postAttrNewTelemetryAnalyzer != null && postAttrNewTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            if (postAttrOldTelemetryAnalyzer != null && postAttrOldTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                                postAttrNewTelemetryAnalyzer.getPathPostParametersAdd().addAll(postAttrOldTelemetryAnalyzer.getPathPostParametersAdd());
//                            }
//                        } else {
//                            if (postAttrOldTelemetryAnalyzer != null && postAttrOldTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                                postAttrNewTelemetryAnalyzer = new ResultsAnalyzerParameters();
//                                postAttrNewTelemetryAnalyzer.getPathPostParametersAdd().addAll(postAttrOldTelemetryAnalyzer.getPathPostParametersAdd());
//                            }
//                        }
//                        ResultsAnalyzerParameters sentTelemetryToThingsboard = null;
//                        if (postAttrNewTelemetryAnalyzer != null && postAttrNewTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            this.updateResourceValueObserve(lwServer, registration, modelClient, postAttrNewTelemetryAnalyzer.getPathPostParametersAdd(), GET_TYPE_OPER_READ);
//                            // prepare sent telemetry to tingsboard only not GET_TYPE_OPER_READ
//                            sentTelemetryToThingsboard = getAnalyzerParameters(postTelemetryAnalyzer.getPathPostParametersAdd(),
//                                    postAttrNewTelemetryAnalyzer.getPathPostParametersAdd());
//                        } else {
//                            sentTelemetryToThingsboard = new ResultsAnalyzerParameters();
//                            sentTelemetryToThingsboard.getPathPostParametersAdd().addAll(new Gson().fromJson(telemetryNew, Set.class));
//                        }
//                        // sent telemetry to tingsboard only not GET_TYPE_OPER_READ
//                        if (sentTelemetryToThingsboard != null && sentTelemetryToThingsboard.getPathPostParametersAdd().size() > 0) {
//                            sentTelemetryToThingsboard.getPathPostParametersAdd().forEach(p -> {
//                                updateAttrTelemetry(registration, false, p);
//                            });
//                        }
//                        // analyze on observe
//                        ResultsAnalyzerParameters postObserveNewTelemetryAnalyzer = getAnalyzerParametersIn(new Gson().fromJson(observeNew, Set.class),
//                                postTelemetryAnalyzer.getPathPostParametersAdd());
//                        if (postObserveNewTelemetryAnalyzer != null && postObserveNewTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            this.updateResourceValueObserve(lwServer, registration, modelClient, postObserveNewTelemetryAnalyzer.getPathPostParametersAdd(), GET_TYPE_OPER_OBSERVE);
//                        }
//                    }
//
//                    // #2.4 del
//                    if (postAttributeAnalyzer != null && postAttributeAnalyzer.getPathPostParametersDel().size() > 0) {
//                        // Analyzer attr and Observe
//                        // Params Attr in Telemetry for  may be not cancel observe
//                        ResultsAnalyzerParameters postAttrTelemetryAnalyzerDel = getAnalyzerParameters(new Gson().fromJson(telemetryNew, Set.class), postAttributeAnalyzer.getPathPostParametersDel());
//                        if (postAttrTelemetryAnalyzerDel != null && postAttrTelemetryAnalyzerDel.getPathPostParametersAdd().size() > 0) {
//                            // Attr dell and cancel observe
//                            ResultsAnalyzerParameters postObserveAtrAnalyzerOldDel = getAnalyzerParametersIn(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostObserveProfile(), Set.class),
//                                    postAttrTelemetryAnalyzerDel.getPathPostParametersAdd());
//                            if (postObserveAtrAnalyzerOldDel != null && postObserveAtrAnalyzerOldDel.getPathPostParametersAdd().size() > 0) {
//                                this.cancelObserveIsValue(lwServer, registration, postObserveAtrAnalyzerOldDel.getPathPostParametersAdd());
//                            }
//                        }
//                    }
//                    if (postTelemetryAnalyzer != null && postTelemetryAnalyzer.getPathPostParametersDel().size() > 0) {
//                        // Analyzer Telemetry and Observe
//                        // Params Telemetry in Attr for  may be not cancel observe
//                        ResultsAnalyzerParameters postTelemetryAttrAnalyzerDel = getAnalyzerParameters(new Gson().fromJson(attributeNew, Set.class),
//                                postTelemetryAnalyzer.getPathPostParametersDel());
//                        if (postTelemetryAttrAnalyzerDel != null && postTelemetryAttrAnalyzerDel.getPathPostParametersAdd().size() > 0) {
//                            // Telemetry dell and cancel observe
//                            ResultsAnalyzerParameters postObserveTelemetryAnalyzerOldDel = getAnalyzerParametersIn(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostObserveProfile(), Set.class),
//                                    postTelemetryAttrAnalyzerDel.getPathPostParametersAdd());
//                            if (postObserveTelemetryAnalyzerOldDel != null && postObserveTelemetryAnalyzerOldDel.getPathPostParametersAdd().size() > 0) {
//                                this.cancelObserveIsValue(lwServer, registration, postObserveTelemetryAnalyzerOldDel.getPathPostParametersAdd());
//                            }
//                        }
//                    }
//                }
//            }
//
//            if (!modelClient.getAttrTelemetryObserveValue().getPostObserveProfile().equals(observeNew)) {
//                // #1.3
//                ResultsAnalyzerParameters postObserveAnalyzer = getAnalyzerParameters(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostObserveProfile(), Set.class),
//                        new Gson().fromJson(observeNew, Set.class));
//                if (postObserveAnalyzer != null) {
//                    // #2.3 add
//                    if (postObserveAnalyzer.getPathPostParametersAdd().size() > 0)
//                        this.updateResourceValueObserve(lwServer, registration, modelClient, postObserveAnalyzer.getPathPostParametersAdd(), GET_TYPE_OPER_OBSERVE);
//                    // #2.5 del
//                    if (postObserveAnalyzer.getPathPostParametersDel().size() > 0) {
//                        // ObserveDel in AttrOld
//                        ResultsAnalyzerParameters observeDelInParameterOld = getAnalyzerParametersIn(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostAttributeProfile(), Set.class),
//                                postObserveAnalyzer.getPathPostParametersDel());
//                        // ObserveDel in TelemetryOld
//                        ResultsAnalyzerParameters observeDelInTelemetry = getAnalyzerParametersIn(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostTelemetryProfile(), Set.class),
//                                postObserveAnalyzer.getPathPostParametersDel());
//                        if (observeDelInTelemetry != null && observeDelInTelemetry.getPathPostParametersAdd().size() > 0) {
//                            if (observeDelInParameterOld == null) {
//                                observeDelInParameterOld = new ResultsAnalyzerParameters();
//                            }
//                            observeDelInParameterOld.getPathPostParametersAdd().addAll(observeDelInTelemetry.getPathPostParametersAdd());
//                        }
//                        if (observeDelInParameterOld != null && observeDelInParameterOld.getPathPostParametersAdd().size() > 0) {
//                            this.cancelObserveIsValue(lwServer, registration, postObserveAnalyzer.getPathPostParametersDel());
//                        }
//                    }
//                }
//
//            }
//            // #3
//            modelClient.getAttrTelemetryObserveValue().setPostAttributeProfile(attributeNew);
//            modelClient.getAttrTelemetryObserveValue().setPostTelemetryProfile(telemetryNew);
//            modelClient.getAttrTelemetryObserveValue().setPostObserveProfile(observeNew);
//        }

    }

    /**
     * Get info about deviceProfile from Thingsboard
     * #1 Equivalence test: old <> new Value (Only Attribute, Telemetry, Observe)
     * #1.1 KeyName
     * #1.2 Attribute
     * #1.3 Telemetry
     * #1.4 Observe
     *
     *
     * #2 If #1 == change, then analyze and update Value in Transport
     * #2.1 Attribute.add:
     * -- if is value in modelClient: add modelClient.getAttrTelemetryObserveValue().getPostAttribute and Get Thingsboard (Attr)
     * -- if is not value in modelClient: nothing
     * #2.2 Telemetry.add:
     * -- if is value in modelClient: add modelClient.getAttrTelemetryObserveValue().getPostTelemetry and Get Thingsboard (Telemetry)
     * -- if is not value in modelClient: nothing
     * #2.3 Observe.add
     * --- if path is in Attr/Telemetry and value not null: =>add to modelClient.getAttrTelemetryObserveValue().getPostObserve() and Request observe to Client
     * # 2.4 del (Attribute, Telemetry)
     * --  #2.5.
     * #2.5 Observe.del
     * -- if value not null: Request cancel observe to client
     * #3 Update in modelClient.getAttrTelemetryObserveValue(): ...Profile
     *
     * @param sessionInfo
     * @param deviceProfile
     */
    public void onDeviceProfileUpdate(TransportProtos.SessionInfoProto sessionInfo, DeviceProfile deviceProfile) {
        log.error("[{}]", sessionInfo);
//        this.updatesAndSentModelParameter(lwServer, registration, modelClient, deviceProfile);

//        String credentialsId = (updateCredentials.getCredentialsIdCount() > 0) ? updateCredentials.getCredentialsId(0) : null;
//        JsonObject credentialsValue = (updateCredentials.getCredentialsValueCount() > 0) ? adaptor.validateJson(updateCredentials.getCredentialsValue(0)) : null;
//        if (credentialsValue != null && !credentialsValue.isJsonNull() && credentialsId != null && !credentialsId.isEmpty()) {
//            String registrationId = lwM2mInMemorySecurityStore.getByRegistrationId(credentialsId);
//            Registration registration = lwM2mInMemorySecurityStore.getByRegistration(registrationId);
//            ModelClient modelClient = lwM2mInMemorySecurityStore.getByRegistrationIdModelClient(registrationId);
//            LeshanServer lwServer = modelClient.getLwServer();
//            log.info("updateDeviceProfile -> registration: {}", registration);
//
//            // #1
//            JsonObject observeAttrNewJson = (credentialsValue.has("observeAttr") && !credentialsValue.get("observeAttr").isJsonNull()) ? credentialsValue.get("observeAttr").getAsJsonObject() : null;
//            log.info("updateCredentials -> observeAttr: {}", observeAttrNewJson);
//            JsonArray attributeNew = (observeAttrNewJson.has("attribute") && !observeAttrNewJson.get("attribute").isJsonNull()) ? observeAttrNewJson.get("attribute").getAsJsonArray() : null;
//            JsonArray telemetryNew = (observeAttrNewJson.has("telemetry") && !observeAttrNewJson.get("telemetry").isJsonNull()) ? observeAttrNewJson.get("telemetry").getAsJsonArray() : null;
//            JsonArray observeNew = (observeAttrNewJson.has("observe") && !observeAttrNewJson.get("observe").isJsonNull()) ? observeAttrNewJson.get("observe").getAsJsonArray() : null;
//            if (!modelClient.getAttrTelemetryObserveValue().getPostAttributeProfile().equals(attributeNew) ||
//                    !modelClient.getAttrTelemetryObserveValue().getPostTelemetryProfile().equals(telemetryNew) ||
//                    !modelClient.getAttrTelemetryObserveValue().getPostObserveProfile().equals(observeNew)) {
//                if (!modelClient.getAttrTelemetryObserveValue().getPostAttributeProfile().equals(attributeNew) ||
//                        !modelClient.getAttrTelemetryObserveValue().getPostTelemetryProfile().equals(telemetryNew)) {
//                    // #1.1
//                    ResultsAnalyzerParameters postAttributeAnalyzer = getAnalyzerParameters(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostAttributeProfile(), Set.class),
//                            new Gson().fromJson(attributeNew, Set.class));
//                    // #1.2
//                    ResultsAnalyzerParameters postTelemetryAnalyzer = getAnalyzerParameters(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostTelemetryProfile(), Set.class),
//                            new Gson().fromJson(telemetryNew, Set.class));
//
//                    // #2 add
//                    // #2.1 add
//                    ResultsAnalyzerParameters postAttrTelemetryOldAnalyzer = null;
//                    if (postAttributeAnalyzer != null && postAttributeAnalyzer.getPathPostParametersAdd().size() > 0) {
//                        // analyze new Attr with old Telemetry
//                        postAttrTelemetryOldAnalyzer = getAnalyzerParameters(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostTelemetryProfile(), Set.class),
//                                postAttributeAnalyzer.getPathPostParametersAdd());
//                        // sent GET_TYPE_OPER_READ if need
//                        ResultsAnalyzerParameters sentAttrToThingsboard = null;
//                        if (postAttrTelemetryOldAnalyzer != null && postAttrTelemetryOldAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            this.updateResourceValueObserve(lwServer, registration, modelClient, postAttrTelemetryOldAnalyzer.getPathPostParametersAdd(), GET_TYPE_OPER_READ);
//                            // prepare sent attr to tingsboard only not GET_TYPE_OPER_READ
//                            sentAttrToThingsboard = getAnalyzerParameters(postAttributeAnalyzer.getPathPostParametersAdd(),
//                                    postAttrTelemetryOldAnalyzer.getPathPostParametersAdd());
//                        } else {
//                            sentAttrToThingsboard = new ResultsAnalyzerParameters();
//                            sentAttrToThingsboard.getPathPostParametersAdd().addAll(new Gson().fromJson(attributeNew, Set.class));
//                        }
//                        // sent attr to tingsboard only not GET_TYPE_OPER_READ
//                        if (sentAttrToThingsboard != null && sentAttrToThingsboard.getPathPostParametersAdd().size() > 0) {
//                            sentAttrToThingsboard.getPathPostParametersAdd().forEach(p -> {
//                                updateAttrTelemetry(registration, false, p);
//                            });
//
//                        }
//                        // analyze on observe
//                        ResultsAnalyzerParameters postObserveNewAttrAnalyzer = getAnalyzerParametersIn(new Gson().fromJson(observeNew, Set.class),
//                                postAttributeAnalyzer.getPathPostParametersAdd());
//                        if (postObserveNewAttrAnalyzer != null && postObserveNewAttrAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            this.updateResourceValueObserve(lwServer, registration, modelClient, postObserveNewAttrAnalyzer.getPathPostParametersAdd(), GET_TYPE_OPER_OBSERVE);
//                        }
//                    }
//                    // #2.2 add
//                    if (postTelemetryAnalyzer != null && postTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                        // analyze new Telemetry with old Attribute
//                        ResultsAnalyzerParameters postAttrOldTelemetryAnalyzer = getAnalyzerParameters(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostAttributeProfile(), Set.class),
//                                postTelemetryAnalyzer.getPathPostParametersAdd());
//                        // analyze new Telemetry with new Attribute
//                        ResultsAnalyzerParameters postAttrNewTelemetryAnalyzer = null;
//                        if (postAttrTelemetryOldAnalyzer != null && postAttrTelemetryOldAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            postAttrNewTelemetryAnalyzer = getAnalyzerParameters(postAttrTelemetryOldAnalyzer.getPathPostParametersAdd(),
//                                    postTelemetryAnalyzer.getPathPostParametersAdd());
//                        }
//                        if (postAttrNewTelemetryAnalyzer != null && postAttrNewTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            if (postAttrOldTelemetryAnalyzer != null && postAttrOldTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                                postAttrNewTelemetryAnalyzer.getPathPostParametersAdd().addAll(postAttrOldTelemetryAnalyzer.getPathPostParametersAdd());
//                            }
//                        } else {
//                            if (postAttrOldTelemetryAnalyzer != null && postAttrOldTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                                postAttrNewTelemetryAnalyzer = new ResultsAnalyzerParameters();
//                                postAttrNewTelemetryAnalyzer.getPathPostParametersAdd().addAll(postAttrOldTelemetryAnalyzer.getPathPostParametersAdd());
//                            }
//                        }
//                        ResultsAnalyzerParameters sentTelemetryToThingsboard = null;
//                        if (postAttrNewTelemetryAnalyzer != null && postAttrNewTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            this.updateResourceValueObserve(lwServer, registration, modelClient, postAttrNewTelemetryAnalyzer.getPathPostParametersAdd(), GET_TYPE_OPER_READ);
//                            // prepare sent telemetry to tingsboard only not GET_TYPE_OPER_READ
//                            sentTelemetryToThingsboard = getAnalyzerParameters(postTelemetryAnalyzer.getPathPostParametersAdd(),
//                                    postAttrNewTelemetryAnalyzer.getPathPostParametersAdd());
//                        } else {
//                            sentTelemetryToThingsboard = new ResultsAnalyzerParameters();
//                            sentTelemetryToThingsboard.getPathPostParametersAdd().addAll(new Gson().fromJson(telemetryNew, Set.class));
//                        }
//                        // sent telemetry to tingsboard only not GET_TYPE_OPER_READ
//                        if (sentTelemetryToThingsboard != null && sentTelemetryToThingsboard.getPathPostParametersAdd().size() > 0) {
//                            sentTelemetryToThingsboard.getPathPostParametersAdd().forEach(p -> {
//                                updateAttrTelemetry(registration, false, p);
//                            });
//                        }
//                        // analyze on observe
//                        ResultsAnalyzerParameters postObserveNewTelemetryAnalyzer = getAnalyzerParametersIn(new Gson().fromJson(observeNew, Set.class),
//                                postTelemetryAnalyzer.getPathPostParametersAdd());
//                        if (postObserveNewTelemetryAnalyzer != null && postObserveNewTelemetryAnalyzer.getPathPostParametersAdd().size() > 0) {
//                            this.updateResourceValueObserve(lwServer, registration, modelClient, postObserveNewTelemetryAnalyzer.getPathPostParametersAdd(), GET_TYPE_OPER_OBSERVE);
//                        }
//                    }
//
//                    // #2.4 del
//                    if (postAttributeAnalyzer != null && postAttributeAnalyzer.getPathPostParametersDel().size() > 0) {
//                        // Analyzer attr and Observe
//                        // Params Attr in Telemetry for  may be not cancel observe
//                        ResultsAnalyzerParameters postAttrTelemetryAnalyzerDel = getAnalyzerParameters(new Gson().fromJson(telemetryNew, Set.class), postAttributeAnalyzer.getPathPostParametersDel());
//                        if (postAttrTelemetryAnalyzerDel != null && postAttrTelemetryAnalyzerDel.getPathPostParametersAdd().size() > 0) {
//                            // Attr dell and cancel observe
//                            ResultsAnalyzerParameters postObserveAtrAnalyzerOldDel = getAnalyzerParametersIn(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostObserveProfile(), Set.class),
//                                    postAttrTelemetryAnalyzerDel.getPathPostParametersAdd());
//                            if (postObserveAtrAnalyzerOldDel != null && postObserveAtrAnalyzerOldDel.getPathPostParametersAdd().size() > 0) {
//                                this.cancelObserveIsValue(lwServer, registration, postObserveAtrAnalyzerOldDel.getPathPostParametersAdd());
//                            }
//                        }
//                    }
//                    if (postTelemetryAnalyzer != null && postTelemetryAnalyzer.getPathPostParametersDel().size() > 0) {
//                        // Analyzer Telemetry and Observe
//                        // Params Telemetry in Attr for  may be not cancel observe
//                        ResultsAnalyzerParameters postTelemetryAttrAnalyzerDel = getAnalyzerParameters(new Gson().fromJson(attributeNew, Set.class),
//                                postTelemetryAnalyzer.getPathPostParametersDel());
//                        if (postTelemetryAttrAnalyzerDel != null && postTelemetryAttrAnalyzerDel.getPathPostParametersAdd().size() > 0) {
//                            // Telemetry dell and cancel observe
//                            ResultsAnalyzerParameters postObserveTelemetryAnalyzerOldDel = getAnalyzerParametersIn(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostObserveProfile(), Set.class),
//                                    postTelemetryAttrAnalyzerDel.getPathPostParametersAdd());
//                            if (postObserveTelemetryAnalyzerOldDel != null && postObserveTelemetryAnalyzerOldDel.getPathPostParametersAdd().size() > 0) {
//                                this.cancelObserveIsValue(lwServer, registration, postObserveTelemetryAnalyzerOldDel.getPathPostParametersAdd());
//                            }
//                        }
//                    }
//                }
//            }
//
//            if (!modelClient.getAttrTelemetryObserveValue().getPostObserveProfile().equals(observeNew)) {
//                // #1.3
//                ResultsAnalyzerParameters postObserveAnalyzer = getAnalyzerParameters(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostObserveProfile(), Set.class),
//                        new Gson().fromJson(observeNew, Set.class));
//                if (postObserveAnalyzer != null) {
//                    // #2.3 add
//                    if (postObserveAnalyzer.getPathPostParametersAdd().size() > 0)
//                        this.updateResourceValueObserve(lwServer, registration, modelClient, postObserveAnalyzer.getPathPostParametersAdd(), GET_TYPE_OPER_OBSERVE);
//                    // #2.5 del
//                    if (postObserveAnalyzer.getPathPostParametersDel().size() > 0) {
//                        // ObserveDel in AttrOld
//                        ResultsAnalyzerParameters observeDelInParameterOld = getAnalyzerParametersIn(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostAttributeProfile(), Set.class),
//                                postObserveAnalyzer.getPathPostParametersDel());
//                        // ObserveDel in TelemetryOld
//                        ResultsAnalyzerParameters observeDelInTelemetry = getAnalyzerParametersIn(new Gson().fromJson(modelClient.getAttrTelemetryObserveValue().getPostTelemetryProfile(), Set.class),
//                                postObserveAnalyzer.getPathPostParametersDel());
//                        if (observeDelInTelemetry != null && observeDelInTelemetry.getPathPostParametersAdd().size() > 0) {
//                            if (observeDelInParameterOld == null) {
//                                observeDelInParameterOld = new ResultsAnalyzerParameters();
//                            }
//                            observeDelInParameterOld.getPathPostParametersAdd().addAll(observeDelInTelemetry.getPathPostParametersAdd());
//                        }
//                        if (observeDelInParameterOld != null && observeDelInParameterOld.getPathPostParametersAdd().size() > 0) {
//                            this.cancelObserveIsValue(lwServer, registration, postObserveAnalyzer.getPathPostParametersDel());
//                        }
//                    }
//                }
//
//            }
//            // #3
//            modelClient.getAttrTelemetryObserveValue().setPostAttributeProfile(attributeNew);
//            modelClient.getAttrTelemetryObserveValue().setPostTelemetryProfile(telemetryNew);
//            modelClient.getAttrTelemetryObserveValue().setPostObserveProfile(observeNew);
//        }

    }

    /**
     * Compare old list with new list  after change AttrTelemetryObserve in config Profile
     *
     * @param parametersOld -
     * @param parametersNew -
     * @return ResultsAnalyzerParameters: add && new
     */
    private ResultsAnalyzerParameters getAnalyzerParameters(Set<String> parametersOld, Set<String> parametersNew) {
        ResultsAnalyzerParameters analyzerParameters = null;
        if (!parametersOld.equals(parametersNew)) {
            analyzerParameters = new ResultsAnalyzerParameters();
            analyzerParameters.setPathPostParametersAdd(parametersNew
                    .stream().filter(p -> !parametersOld.contains(p)).collect(Collectors.toSet()));
            analyzerParameters.setPathPostParametersDel(parametersOld
                    .stream().filter(p -> !parametersNew.contains(p)).collect(Collectors.toSet()));
        }
        return analyzerParameters;
    }

    private ResultsAnalyzerParameters getAnalyzerParametersIn(Set<String> parametersObserve, Set<String> parameters) {
        ResultsAnalyzerParameters analyzerParameters = new ResultsAnalyzerParameters();
        analyzerParameters.setPathPostParametersAdd(parametersObserve
                .stream().filter(p -> parameters.contains(p)).collect(Collectors.toSet()));
        return analyzerParameters;
    }

    /**
     * Update Resource value after change RezAttrTelemetry in config Profile
     * sent response Read to Client and add path to pathResAttrTelemetry in ModelClient.getAttrTelemetryObserveValue()
     *
     * @param lwServer     - LeshanServer
     * @param registration - Registration LwM2M Client
     * @param modelClient  - object with All parameters off client
     * @param targets      - path Resources
     */
    private void updateResourceValueObserve(LeshanServer lwServer, Registration registration, ModelClient modelClient, Set<String> targets, String typeOper) {
        targets.stream().forEach(target -> {
            ResultIds pathIds = new ResultIds(target);
            if (pathIds.resourceId >= 0 && modelClient.getModelObjects().get(pathIds.getObjectId())
                    .getInstances().get(pathIds.getInstanceId()).getResource(pathIds.getResourceId()).getValue() != null) {
                if (GET_TYPE_OPER_READ.equals(typeOper)) {
                    lwM2MTransportRequest.sendAllRequest(lwServer, registration, target, typeOper,
                            ContentFormat.TLV.getName(), null, null, "", this.context.getCtxServer().getTimeout());
                } else if (GET_TYPE_OPER_OBSERVE.equals(typeOper)) {
                    lwM2MTransportRequest.sendAllRequest(lwServer, registration, target, typeOper,
                            null, null, null, "", this.context.getCtxServer().getTimeout());
                }
            }
        });
    }

    private void cancelObserveIsValue(LeshanServer lwServer, Registration registration, Set<String> paramAnallyzer) {
        paramAnallyzer.forEach(p -> {
                    //TODO if is value
                    this.setCancelObservationRecourse(lwServer, registration, p);
                }
        );
    }

    /**
     * Trigger Server path = "/1/0/8"
     * TODO
     * Trigger bootStrap path = "/1/0/9" - have to implemented on client
     */
    public void doTrigger(LeshanServer lwServer, Registration registration, String path) {
        lwM2MTransportRequest.sendAllRequest(lwServer, registration, path, POST_TYPE_OPER_EXECUTE,
                ContentFormat.TLV.getName(), null, null, "", this.context.getCtxServer().getTimeout());
    }

    /**
     * Session device in thingsboard is closed
     *
     * @param sessionInfo - lwm2m client
     */
    private void doCloseSession(SessionInfoProto sessionInfo) {
        TransportProtos.SessionEvent event = SessionEvent.CLOSED;
        TransportProtos.SessionEventMsg msg = TransportProtos.SessionEventMsg.newBuilder()
                .setSessionType(TransportProtos.SessionType.ASYNC)
                .setEvent(event).build();
        transportService.process(sessionInfo, msg, null);
    }

    /**
     * Deregister session in transport
     *
     * @param sessionInfo - lwm2m client
     */
    private void doDisconnect(SessionInfoProto sessionInfo) {
        transportService.process(sessionInfo, DefaultTransportService.getSessionEventMsg(SessionEvent.CLOSED), null);
        transportService.deregisterSession(sessionInfo);
    }

    private void checkInactivityAndReportActivity() {
        lwM2mInMemorySecurityStore.getSessions().forEach((key, value) -> transportService.reportActivity(this.getValidateSessionInfo(key)));
    }

}
