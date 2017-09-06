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

import static com.flow.platform.api.config.AppConfig.ALLOW_SIZE;
import static com.flow.platform.api.config.AppConfig.ALLOW_SUFFIX;

import com.flow.platform.api.dao.CredentialStorageDao;
import com.flow.platform.api.domain.credential.Credential;
import com.flow.platform.api.domain.credential.CredentialStorage;
import com.flow.platform.api.domain.credential.CredentialType;
import com.flow.platform.api.domain.credential.RSAKeyPair;
import com.flow.platform.core.exception.IllegalParameterException;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author lhl
 */

@Service(value = "credentialService")
public class CredentialServiceImpl implements CredentialService {

    @Autowired
    private CredentialStorageDao credentialStorageDao;

    @Override
    public Credential create(Credential credential) {
        CredentialStorage credentialStorage = new CredentialStorage(credential, ZonedDateTime.now(),
            ZonedDateTime.now());
        if(findCredentialByName(credential.getName()) != null){
            throw new IllegalParameterException("name is already present");
        } else {
            credentialStorageDao.save(credentialStorage);
            return credential;
        }

    }

    @Override
    public Credential find(String name) {
        return findCredentialByName(name).getContent();
    }

    @Override
    public Credential update(Credential credential) {
        CredentialStorage credentialStorage = findCredentialByName(credential.getName());
        credentialStorage.setContent(credential);
        credentialStorageDao.update(credentialStorage);
        return credential;
    }

    @Override
    public void delete(String name) {
        CredentialStorage credentialStorage = findCredentialByName(name);
        credentialStorageDao.delete(credentialStorage);
    }

    @Override
    public List<Credential> listCredentials() {
        return listCertificate();
    }

    @Override
    public List<Credential> listTypes(String credentialType) {
        List<Credential> list = new ArrayList<>();
        CredentialType credentialType1 = CredentialType.valueOf(credentialType);
        List<Credential> list_certificate = listCertificate();
        for (Credential credential : list_certificate) {
            if (credential.getCredentialType() == credentialType1) {
                list.add(credential);
            }
        }
        return list;
    }


    @Override
    public RSAKeyPair generateRsaKey() {
        String comment = "FLOWCI";
        int type = KeyPair.RSA;
        JSch jsch = new JSch();

        try {
            KeyPair kpair = KeyPair.genKeyPair(jsch, type);
            RSAKeyPair pair = new RSAKeyPair();

            // private key
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                kpair.writePrivateKey(baos);
                pair.setPrivateKey(baos.toString());
            }

            // public key
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                kpair.writePublicKey(baos, comment);
                pair.setPublicKey(baos.toString());
            }

            kpair.dispose();
            return pair;

        } catch (JSchException | IOException e) {
            return null;
        }
    }

    public long getAllowSize(){
        return ALLOW_SIZE;
    }

    public String allowSuffix(){
        return ALLOW_SUFFIX;
    }

    private CredentialStorage findCredentialByName(String name) {
        for (CredentialStorage credentialStorage : credentialStorageDao.list()) {
            if (credentialStorage.getContent().getName().equals(name)) {
                return credentialStorage;
            }
        }
        return null;
    }

    private List<Credential> listCertificate() {
        List<Credential> list = new ArrayList<>();
        for (CredentialStorage credentialStorage : credentialStorageDao.list()) {
            list.add(credentialStorage.getContent());
        }
        return list;
    }
}