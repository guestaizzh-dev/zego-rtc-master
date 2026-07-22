package com.toyeah.dispatching.controller;

import com.toyeah.dispatching.dto.RtcSessionRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiControllerTest {

    @Test
    void assignsRandomZegoUserIDForNewSession() throws Exception {
        RtcSessionRequest request = new RtcSessionRequest();
        request.setUserID("doctor_84");
        request.setStreamID("legacy_stream");

        prepare(request);

        assertTrue(request.getUserID().startsWith("doctor_84_"));
        assertEquals(34, request.getUserID().length());
        assertNull(request.getStreamID());
    }

    @Test
    void keepsAssignedZegoUserIDWhenRenewingToken() throws Exception {
        RtcSessionRequest request = new RtcSessionRequest();
        request.setUserID("doctor_84_existing_session");
        request.setStreamID("room_stream");
        request.setLeaseID("lease-1");

        prepare(request);

        assertEquals("doctor_84_existing_session", request.getUserID());
        assertEquals("room_stream", request.getStreamID());
    }

    @Test
    void generatesDistinctIDsForDistinctSessions() throws Exception {
        RtcSessionRequest first = new RtcSessionRequest();
        first.setUserID("shop_2312");
        RtcSessionRequest second = new RtcSessionRequest();
        second.setUserID("shop_2312");

        prepare(first);
        prepare(second);

        assertNotEquals(first.getUserID(), second.getUserID());
    }

    private void prepare(RtcSessionRequest request) throws Exception {
        ApiController controller = new ApiController(null, null, null);
        Method method = ApiController.class.getDeclaredMethod("prepareZegoUserID", RtcSessionRequest.class);
        method.setAccessible(true);
        method.invoke(controller, request);
    }
}
