/*
 * Copyright 2017 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flow.platform.api.service;

import com.flow.platform.api.dao.job.JobDao;
import com.flow.platform.api.domain.AgentWithFlow;
import com.flow.platform.api.domain.job.Job;
import com.flow.platform.api.domain.job.JobStatus;
import com.flow.platform.api.domain.job.NodeStatus;
import com.flow.platform.api.events.AgentStatusChangeEvent;
import com.flow.platform.api.service.job.CmdService;
import com.flow.platform.api.service.job.JobService;
import com.flow.platform.api.util.PlatformURL;
import com.flow.platform.core.exception.HttpException;
import com.flow.platform.core.exception.IllegalParameterException;
import com.flow.platform.core.exception.IllegalStatusException;
import com.flow.platform.core.service.ApplicationEventService;
import com.flow.platform.domain.Agent;
import com.flow.platform.domain.AgentPath;
import com.flow.platform.domain.AgentPathWithWebhook;
import com.flow.platform.domain.AgentSettings;
import com.flow.platform.domain.AgentStatus;
import com.flow.platform.domain.CmdInfo;
import com.flow.platform.domain.CmdType;
import com.flow.platform.domain.Jsonable;
import com.flow.platform.util.CollectionUtil;
import com.flow.platform.util.Logger;
import com.flow.platform.util.StringUtil;
import com.flow.platform.util.http.HttpClient;
import com.flow.platform.util.http.HttpResponse;
import com.flow.platform.util.http.HttpURL;
import com.google.common.base.Strings;
import com.google.gson.JsonSyntaxException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.http.entity.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * @author yh@firim
 */

@Service
public class AgentServiceImpl extends ApplicationEventService implements AgentService {

    private final Logger LOGGER = new Logger(AgentService.class);

    private final int httpRetryTimes = 5;

    @Value(value = "${api.zone.default}")
    private String zone;

    @Autowired
    private JobDao jobDao;

    @Autowired
    private PlatformURL platformURL;

    @Autowired
    private CmdService cmdService;

    @Autowired
    private JobService jobService;

    @Value(value = "${domain.api}")
    private String apiDomain;

    @Override
    public List<AgentWithFlow> list() {
        HttpResponse<String> response = HttpClient.build(platformURL.getAgentUrl())
            .get()
            .retry(httpRetryTimes)
            .bodyAsString();

        if (!response.hasSuccess()) {
            throw new HttpException("Unable to load agent list");
        }

        Agent[] agents = Jsonable.GSON_CONFIG.fromJson(response.getBody(), Agent[].class);

        // get all session id from agent collection
        List<String> sessionIds = CollectionUtil.toPropertyList("sessionId", agents);

        // get all running jobs from agent sessions
        List<Job> jobs = new ArrayList<>(0);
        if (!CollectionUtil.isNullOrEmpty(sessionIds)) {
            jobs = jobDao.list(sessionIds, NodeStatus.RUNNING);
        }

        // convert to session - job map
        Map<String, Job> sessionJobMap = CollectionUtil.toPropertyMap("sessionId", jobs);
        if (CollectionUtil.isNullOrEmpty(sessionJobMap)) {
            sessionJobMap = new HashMap<>(0);
        }

        // build result list
        List<AgentWithFlow> agentWithFlows = new ArrayList<>(agents.length);
        for (Agent agent : agents) {
            if (agent.getStatus() == AgentStatus.OFFLINE){
                agentWithFlows.add(new AgentWithFlow(agent, null));
                continue;
            }

            String sessionIdFromAgent = agent.getSessionId();

            if (Strings.isNullOrEmpty(sessionIdFromAgent)) {
                agentWithFlows.add(new AgentWithFlow(agent, null));
                continue;
            }

            Job job = sessionJobMap.get(sessionIdFromAgent);
            if (job == null) {
                agentWithFlows.add(new AgentWithFlow(agent, null));
                continue;
            }

            agentWithFlows.add(new AgentWithFlow(agent, job));
        }
        return agentWithFlows;
    }

    @Override
    public Boolean shutdown(AgentPath path, String password) {
        try {
            cmdService.shutdown(path, password);
            return true;
        } catch (IllegalStatusException e) {
            LOGGER.warnMarker("shutdown", e.getMessage());
            return false;
        }
    }

    @Override
    public Boolean close(AgentPath path) {
        try {
            cmdService.close(path);
            return true;
        } catch (IllegalStatusException e) {
            LOGGER.warnMarker("close", e.getMessage());
            return false;
        }
    }

    @Override
    public AgentWithFlow create(AgentPath agentPath) {
        if (StringUtil.hasSpace(agentPath.getZone()) || StringUtil.hasSpace(agentPath.getName())) {
            throw new IllegalParameterException("Zone name or agent name cannot contain empty space");
        }

        try {
            AgentPathWithWebhook pathWithWebhook = new AgentPathWithWebhook(agentPath, buildAgentWebhook());

            HttpResponse<String> response = HttpClient.build(platformURL.getAgentCreateUrl())
                .post(pathWithWebhook.toJson())
                .withContentType(ContentType.APPLICATION_JSON)
                .retry(httpRetryTimes)
                .bodyAsString();

            if (!response.hasSuccess()) {
                throw new HttpException("Unable to create agent via control center");
            }

            Agent agent = Agent.parse(response.getBody(), Agent.class);
            return new AgentWithFlow(agent, null);

        } catch (UnsupportedEncodingException | JsonSyntaxException e) {
            throw new IllegalStatusException("Unable to create agent", e);
        }
    }

    @Override
    public AgentSettings settings(String token) {
        String url = platformURL.getAgentSettingsUrl() + "?" + "token=" + token;
        HttpResponse<String> response = HttpClient.build(url)
            .get()
            .retry(httpRetryTimes)
            .bodyAsString();

        if (!response.hasSuccess()) {
            throw new HttpException("Unable to get agent settings from control center");
        }

        return AgentSettings.parse(response.getBody(), AgentSettings.class);
    }

    @Override
    public void delete(AgentPath agentPath) {
        Agent agent = findAgent(agentPath);

        try {
            HttpClient.build(platformURL.getAgentDeleteUrl())
                .post(agent.toJson())
                .withContentType(ContentType.APPLICATION_JSON)
                .retry(httpRetryTimes)
                .bodyAsString();

        } catch (UnsupportedEncodingException e) {
            throw new IllegalStatusException(e.getMessage());
        }
    }

    @Override
    public void sendSysCmd(AgentPath agentPath) {
        CmdInfo cmdInfo = new CmdInfo(agentPath, CmdType.SYSTEM_INFO, "");
        cmdService.sendCmd(agentPath, cmdInfo);
    }

    private String buildAgentWebhook() {
        return HttpURL.build(apiDomain).append("/agents/callback").toString();
    }

    /**
     * find agent
     */
    private Agent findAgent(AgentPath agentPath) {
        String url =
            platformURL.getAgentFindUrl() + "?" + "zone=" + agentPath.getZone() + "&" + "name=" + agentPath.getName();
        HttpResponse<String> response = HttpClient.build(url)
            .get()
            .retry(httpRetryTimes)
            .bodyAsString();

        if (!response.hasSuccess()) {
            throw new HttpException("Unable to delete agent");
        }

        Agent agent = Agent.parse(response.getBody(), Agent.class);

        if (agent == null){
            throw new IllegalStatusException("agent is not exist");
        }

        if (agent.getStatus() == AgentStatus.BUSY) {
            throw new IllegalStatusException("agent is busy, please wait");
        }

        return agent;
    }

    @Override
    public void onAgentStatusChange(Agent agent) {
        this.dispatchEvent(new AgentStatusChangeEvent(this, agent));

        // do not check related job if agent status not offline
        if (agent.getStatus() != AgentStatus.OFFLINE) {
            return;
        }

        // find related job and set job to failure
        String sessionId = agent.getSessionId();
        if (Strings.isNullOrEmpty(sessionId)) {
            return;
        }

        // find agent related job by session id
        Job job = jobService.find(sessionId);
        if (job == null) {
            return;
        }

        if (Job.RUNNING_STATUS.contains(job.getStatus())) {
            job.setFailureMessage(String.format("Agent %s is offline when job running", agent.getPath()));
            jobService.updateJobStatusAndSave(job, JobStatus.FAILURE);
        }
    }
}
