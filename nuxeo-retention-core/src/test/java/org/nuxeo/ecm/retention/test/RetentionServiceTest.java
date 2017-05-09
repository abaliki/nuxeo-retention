/*
 * (C) Copyright 2017 Nuxeo (http://nuxeo.com/) and others.
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
 *     mcedica@nuxeo.com
 */

package org.nuxeo.ecm.retention.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.TransactionalFeature;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.ecm.retention.adapter.Record;
import org.nuxeo.ecm.retention.adapter.RetentionRule;
import org.nuxeo.ecm.retention.service.RetentionService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.google.inject.Inject;

@RunWith(FeaturesRunner.class)
@Features({ TransactionalFeature.class, AutomationFeature.class })
@Deploy({ "org.nuxeo.ecm.platform.query.api", "org.nuxeo.ecm.retention.service.nuxeo-retention-service" })
@LocalDeploy("org.nuxeo.ecm.retention.service.nuxeo-retention-service:retention-rules-contrib-test.xml")
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
public class RetentionServiceTest {

    @Inject
    CoreSession session;

    @Inject
    RetentionService service;

    @Inject
    WorkManager workManager;

    @Inject
    UserManager userManager;

    @Inject
    CoreFeature settings;

    @Test
    public void testRetentionService() throws InterruptedException {
        List<DocumentModel> docs = new ArrayList<DocumentModel>();
        DocumentModel doc;
        for (int i = 0; i < 5; i++) {
            doc = session.createDocumentModel("/", "root", "Folder");
            doc = session.createDocument(doc);
            docs.add(doc);
        }
        session.save();
        service.attachRule("myTestRuleId2", "Select * from Folder", session);
        DocumentModelList folders = session.query("Select * from Folder");
        for (DocumentModel f : folders) {
            f.setPropertyValue("dc:title", "blah");
            f = session.saveDocument(f);
        }

        waitForWorkers();
        session.save();
        for (DocumentModel documentModel : docs) {
            documentModel = session.getDocument(documentModel.getRef());
            assertTrue(documentModel.hasFacet(RetentionService.RECORD_FACET));
        }

    }

    @Test
    public void testStartRetentionOnModifiedEvent() throws Exception {
        RetentionRule rule = service.getRetentionRule("myTestRuleId", session);
        assertNotNull(rule);
        DocumentModel doc = session.createDocumentModel("/", "root", "Folder");
        doc = session.createDocument(doc);
        service.attachRule(rule.getId(), doc);
        doc = session.getDocument(doc.getRef());
        waitForWorkers();
        // modify the document to see if the rule is invoked
        doc.setPropertyValue("dc:title", "Blahhaa");
        doc = session.saveDocument(doc);
        waitForWorkers();
        assertTrue(doc.isLocked());
        Record record = doc.getAdapter(Record.class);
        assertNotNull(record);
        assertEquals("active", record.getStatus());
        // wait for the retention to end
        Thread.sleep(rule.getRetentionDurationInMillis() + 1000);
        Framework.getLocalService(EventService.class).fireEvent(RetentionService.RETENTION_CHECKER_EVENT,
                new DocumentEventContext(session, null, doc));
        waitForWorkers();
        doc = session.getDocument(doc.getRef());
        record = doc.getAdapter(Record.class);
        assertFalse(doc.isLocked());
        assertEquals("expired", record.getStatus());
    }

    @Test
    public void testStartRetentionOnCreationEvent() throws Exception {
        RetentionRule rule = service.getRetentionRule("retentionOnCreation", session);
        assertNotNull(rule);
        DocumentModel doc = session.createDocumentModel("/", "root", "File");
        doc = session.createDocument(doc);
        service.attachRule(rule.getId(), doc);
        waitForWorkers();
        assertTrue(doc.isLocked());
        doc = session.getDocument(doc.getRef());
        Record record = doc.getAdapter(Record.class);
        assertNotNull(record);
        assertEquals("active", record.getStatus());
    }

    @Test
    public void testStartRetentionWithDelay() throws Exception {
        RetentionRule rule = service.getRetentionRule("retentionWithDelay", session);
        assertNotNull(rule);
        DocumentModel doc = session.createDocumentModel("/", "root", "File");
        doc = session.createDocument(doc);
        service.attachRule(rule.getId(), doc);
        session.save();

        CoreSession sessionAsJdoe = settings.openCoreSession("jdoe");
        ACP acp = doc.getACP();
        ACL localACL = acp.getOrCreateACL(ACL.LOCAL_ACL);
        localACL.add(new ACE("jdoe", SecurityConstants.READ_WRITE, true));
        doc.setACP(acp, true);
        doc = session.saveDocument(doc);
        doc.setPropertyValue("dc:title", "ddd");

        doc = session.getDocument(doc.getRef());
        Record record = doc.getAdapter(Record.class);
        doc = sessionAsJdoe.saveDocument(doc);

        Thread.sleep(rule.getBeginDelayInMillis() + 1000);
        Framework.getLocalService(EventService.class).fireEvent(RetentionService.RETENTION_CHECKER_EVENT,
                new DocumentEventContext(session, null, doc));

        waitForWorkers();
        doc = session.getDocument(doc.getRef());
        assertTrue(doc.isLocked());
        record = doc.getAdapter(Record.class);
        assertNotNull(record);
        assertEquals("active", record.getStatus());
        Exception e = null;
        try {
            doc = sessionAsJdoe.saveDocument(doc);
        } catch (DocumentSecurityException e1) {
            e = e1;
        }
        assertNotNull(e);
        sessionAsJdoe.close();

    }

    @Test
    public void testRules() {
        // we are deploying a static rule
        RetentionRule rule = service.getRetentionRule("myTestRuleId", session);
        assertNotNull(rule);
        assertEquals("myTestRuleId", rule.getId());
        assertTrue(0 == rule.getBeginDelayInMillis());
        assertTrue(10000 == rule.getRetentionDurationInMillis());

        // add a dynamic rule
        DocumentModel doc = session.createDocumentModel("/", "root", "Folder");
        doc = session.createDocument(doc);
        String ruleId = service.createOrUpdateDynamicRuleRuleOnDocument(null, null, 0, null, "endAction", null,
                "documentUpdated", null, doc, session);
        assertEquals(ruleId, doc.getId());
        rule = service.getRetentionRule(ruleId, session);
        assertNull(rule.getBeginAction());
        assertEquals("endAction", rule.getEndAction());
        assertEquals("documentUpdated", rule.getBeginCondition().getEvent());

    }

    protected void waitForWorkers() throws InterruptedException {
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();

        final boolean allCompleted = workManager.awaitCompletion(100, TimeUnit.SECONDS);
        assertTrue(allCompleted);
    }

}
