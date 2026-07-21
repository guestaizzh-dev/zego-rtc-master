package com.toyeah.dispatching.service;

import com.toyeah.dispatching.dto.RtcRoomRecord;
import com.toyeah.dispatching.dto.RtcSessionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoomAccessServiceTest {

    private RoomAccessService service;

    @BeforeEach
    void setUp() {
        service = new RoomAccessService(new RtcRoomPersistenceService());
    }

    @Test
    void newPageReplacesLeaseAndOldPageCannotReleaseSlot() {
        RtcSessionRequest first = request("room-1", "doctor-1", "doctor");
        String firstLease = service.enter(first).getLeaseID();

        RtcSessionRequest second = request("room-1", "doctor-1", "doctor");
        String secondLease = service.enter(second).getLeaseID();
        assertNotEquals(firstLease, secondLease);

        first.setLeaseID(firstLease);
        assertThrows(IllegalArgumentException.class, () -> service.leave(first));

        second.setLeaseID(secondLease);
        service.heartbeat(second);
        assertEquals("doctor-1", service.findRoom("room-1").getDoctorUserID());
    }

    @Test
    void tokenRenewalKeepsCurrentLease() {
        RtcSessionRequest request = request("room-2", "peer-1", "mp");
        String lease = service.enter(request).getLeaseID();
        request.setLeaseID(lease);

        assertEquals(lease, service.enter(request).getLeaseID());
        service.heartbeat(request);
    }

    @Test
    void failedTakeoverRollsBackToPreviousLease() {
        RtcSessionRequest first = request("room-3", "doctor-1", "doctor");
        String firstLease = service.enter(first).getLeaseID();

        RtcSessionRequest takeover = request("room-3", "doctor-1", "doctor");
        RoomAccessService.EnterResult result = service.enter(takeover);
        service.rollbackEnter(takeover, result);

        first.setLeaseID(firstLease);
        service.heartbeat(first);
    }

    @Test
    void differentUserCannotOccupySameRoleSlot() {
        service.enter(request("room-4", "doctor-1", "doctor"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.enter(request("room-4", "doctor-2", "doctor")));
        assertEquals("医生已在其他设备进入", error.getMessage());
    }

    @Test
    void doctorCanEndRoomAndFurtherEntryIsRejected() {
        RtcSessionRequest doctor = request("room-5", "doctor-1", "doctor");
        doctor.setLeaseID(service.enter(doctor).getLeaseID());
        service.end(doctor);

        RtcRoomRecord room = service.findRoom("room-5");
        assertEquals("ENDED", room.getStatus());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.enter(request("room-5", "peer-1", "mp")));
        assertEquals("当前订单已结束", error.getMessage());
    }

    @Test
    void peerCannotEndRoom() {
        RtcSessionRequest doctor = request("room-6", "doctor-1", "doctor");
        doctor.setLeaseID(service.enter(doctor).getLeaseID());
        RtcSessionRequest peer = request("room-6", "shop-1", "pharmacy");
        peer.setLeaseID(service.enter(peer).getLeaseID());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.end(peer));
        assertEquals("只有医生可以结束房间", error.getMessage());
        assertEquals("ACTIVE", service.findRoom("room-6").getStatus());
        service.heartbeat(doctor);
    }

    private RtcSessionRequest request(String roomID, String userID, String role) {
        RtcSessionRequest request = new RtcSessionRequest();
        request.setRoomID(roomID);
        request.setUserID(userID);
        request.setStreamID(roomID.replace("-", "_") + "_" + userID.replace("-", "_"));
        request.setRole(role);
        request.setClientType("test");
        return request;
    }
}
