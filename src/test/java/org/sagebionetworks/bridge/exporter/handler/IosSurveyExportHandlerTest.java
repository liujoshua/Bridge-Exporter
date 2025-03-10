package org.sagebionetworks.bridge.exporter.handler;

import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;

import java.io.File;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Multiset;
import org.joda.time.LocalDate;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.exporter.dynamo.DynamoHelper;
import org.sagebionetworks.bridge.exporter.helper.ExportHelper;
import org.sagebionetworks.bridge.exporter.metrics.Metrics;
import org.sagebionetworks.bridge.exporter.request.BridgeExporterRequest;
import org.sagebionetworks.bridge.exporter.worker.ExportSubtask;
import org.sagebionetworks.bridge.exporter.worker.ExportTask;
import org.sagebionetworks.bridge.exporter.worker.ExportWorkerManager;
import org.sagebionetworks.bridge.json.DefaultObjectMapper;
import org.sagebionetworks.bridge.schema.UploadSchema;
import org.sagebionetworks.bridge.schema.UploadSchemaKey;

// Test strategy here is a one question survey. Survey conversion is tested in the ExportHelper, so we just only test
// data flow here.
public class IosSurveyExportHandlerTest {
    // Records here are only used for record ID, so we can share a singleton across tests.
    private static final String DUMMY_RECORD_ID = "dummy-record-id";
    private static final Item DUMMY_RECORD = new Item().withString("id", DUMMY_RECORD_ID);

    // These are only needed to ensure valid tasks and subtasks.
    private static final LocalDate DUMMY_REQUEST_DATE = LocalDate.parse("2015-10-31");
    private static final BridgeExporterRequest DUMMY_REQUEST = new BridgeExporterRequest.Builder()
            .withDate(DUMMY_REQUEST_DATE).build();
    private static final String TEST_STUDY_ID = "testStudy";
    private static final UploadSchemaKey SCHEMA_KEY_PLACEHOLDER = new UploadSchemaKey.Builder()
            .withStudyId(TEST_STUDY_ID).withSchemaId("ios-survey").withRevision(1).build();
    private static final UploadSchemaKey SCHEMA_KEY_REAL = new UploadSchemaKey.Builder()
            .withStudyId(TEST_STUDY_ID).withSchemaId("Daily-Quiz").withRevision(1).build();

    ExportSubtask.Builder subtaskBuilder;
    IosSurveyExportHandler handler;

    @BeforeMethod
    public void setup() throws Exception {
        // set up manager
        ExportWorkerManager manager = spy(new ExportWorkerManager());
        manager.setDynamoHelper(mock(DynamoHelper.class));
        manager.setExportHelper(mock(ExportHelper.class));

        // set up handler
        handler = new IosSurveyExportHandler();
        handler.setManager(manager);
        handler.setStudyId(TEST_STUDY_ID);

        // set up task
        ExportTask task = new ExportTask.Builder().withExporterDate(DUMMY_REQUEST_DATE).withMetrics(new Metrics())
                .withRequest(DUMMY_REQUEST).withTmpDir(mock(File.class)).build();

        // set up subtask (minus record JSON)
        subtaskBuilder = new ExportSubtask.Builder().withOriginalRecord(DUMMY_RECORD).withParentTask(task)
                .withSchemaKey(SCHEMA_KEY_PLACEHOLDER);
    }

    @DataProvider(name = "errorCaseJsonProvider")
    public Object[][] errorCaseJsonProvider() {
        return new Object[][] {
                // no item
                { "{}" },

                // null item
                { "{\"item\":null}" },

                // item not string
                { "{\"item\":42}" },

                // item empty string
                { "{\"item\":\"\"}" },
        };
    }

    @Test(dataProvider = "errorCaseJsonProvider")
    public void errorCase(String recordDataJsonText) throws Exception {
        // set up and execute
        JsonNode recordDataJsonNode = DefaultObjectMapper.INSTANCE.readTree(recordDataJsonText);
        ExportSubtask subtask = subtaskBuilder.withRecordData(recordDataJsonNode).build();
        handler.handle(subtask);

        // verify that the manager's mock helpers were not called
        ExportWorkerManager manager = handler.getManager();
        verifyZeroInteractions(manager.getDynamoHelper(), manager.getExportHelper());

        // verify metrics
        Multiset<String> counterMap = subtask.getParentTask().getMetrics().getCounterMap();
        assertEquals(counterMap.count("surveyWorker[" + TEST_STUDY_ID + "].surveyCount"), 0);
        assertEquals(counterMap.count("surveyWorker[" + TEST_STUDY_ID + "].errorCount"), 1);
    }

    @Test
    public void normalCase() throws Exception {
        // record data JSON
        String recordDataJsonText = "{\n" +
                "   \"item\":\"Daily-Quiz\",\n" +
                "   \"answers\":\"dummy-attachment-id\"\n" +
                "}";
        JsonNode recordDataJsonNode = DefaultObjectMapper.INSTANCE.readTree(recordDataJsonText);
        ExportSubtask subtask = subtaskBuilder.withRecordData(recordDataJsonNode).build();

        // get metrics
        ExportTask task = subtask.getParentTask();
        Metrics metrics = task.getMetrics();

        // get manager
        ExportWorkerManager manager = handler.getManager();

        // mock Dynamo Helper
        UploadSchema schema = new UploadSchema.Builder().withKey(SCHEMA_KEY_REAL).addField("foo", "STRING").build();
        when(manager.getDynamoHelper().getSchema(metrics, SCHEMA_KEY_REAL)).thenReturn(schema);

        // mock Export Helper
        JsonNode convertedSurveyNode = DefaultObjectMapper.INSTANCE.createObjectNode();
        when(manager.getExportHelper().convertSurveyRecordToHealthDataJsonNode(DUMMY_RECORD_ID, recordDataJsonNode,
                schema)).thenReturn(convertedSurveyNode);

        // spy manager.addHealthDataSubtask()
        ArgumentCaptor<ExportSubtask> convertedSubtaskCaptor = ArgumentCaptor.forClass(ExportSubtask.class);
        doNothing().when(manager).addHealthDataSubtask(same(task), eq(TEST_STUDY_ID), eq(SCHEMA_KEY_REAL),
                convertedSubtaskCaptor.capture());

        // execute
        handler.handle(subtask);

        // validate converted subtask
        ExportSubtask convertedSubtask = convertedSubtaskCaptor.getValue();
        assertSame(convertedSubtask.getOriginalRecord(), DUMMY_RECORD);
        assertSame(convertedSubtask.getParentTask(), task);
        assertSame(convertedSubtask.getRecordData(), convertedSurveyNode);
        assertEquals(convertedSubtask.getSchemaKey(), SCHEMA_KEY_REAL);

        // verify metrics
        Multiset<String> counterMap = subtask.getParentTask().getMetrics().getCounterMap();
        assertEquals(counterMap.count("surveyWorker[" + TEST_STUDY_ID + "].surveyCount"), 1);
        assertEquals(counterMap.count("surveyWorker[" + TEST_STUDY_ID + "].errorCount"), 0);
    }
}
