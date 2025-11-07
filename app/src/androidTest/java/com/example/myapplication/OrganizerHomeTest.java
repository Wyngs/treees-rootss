package com.example.myapplication;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.myapplication.data.firebase.FirebaseEventRepository;
import com.example.myapplication.features.user.UserEvent;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Instrumented tests for Organizer Home page functionality.
 *
 * <p>These tests verify that:
 * <ul>
 * <li>Events created by the organizer are successfully loaded from Firestore</li>
 * <li>Only the organizer's own events are displayed</li>
 * <li>Multiple events can be fetched and filtered correctly</li>
 * <li>Event data fields are retrieved correctly for organizer view</li>
 * <li>Empty event lists are handled gracefully</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public class OrganizerHomeTest {

    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private FirebaseEventRepository eventRepository;

    private String organizerUserId;
    private String organizerEmail;
    private String organizerPassword;
    private List<String> createdEventIds;

    @Before
    public void setUp() throws Exception {
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        eventRepository = new FirebaseEventRepository();
        createdEventIds = new ArrayList<>();

        organizerPassword = "testPassword123";
        organizerEmail = "test_organizer_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";

        // Create a test organizer user
        AuthResult authResult = Tasks.await(
            auth.createUserWithEmailAndPassword(organizerEmail, organizerPassword),
            30,
            TimeUnit.SECONDS
        );
        organizerUserId = authResult.getUser().getUid();
    }

    @After
    public void tearDown() throws Exception {
        // Clean up: delete all created test events
        for (String eventId : createdEventIds) {
            try {
                Tasks.await(db.collection("events").document(eventId).delete(), 10, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Cleanup error for event: " + e.getMessage());
            }
        }

        // Clean up: delete test user
        if (organizerUserId != null) {
            try {
                Tasks.await(db.collection("users").document(organizerUserId).delete(), 10, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Cleanup error for user: " + e.getMessage());
            }
        }

        // Sign out
        if (auth.getCurrentUser() != null) {
            auth.signOut();
        }
    }

    /**
     * Test fetching events created by the organizer.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testFetchOrganizerOwnEvents() throws Exception {
        // Create multiple events owned by the organizer
        createOrganizerEvent("My Event 1", "Location 1", 15.0);
        createOrganizerEvent("My Event 2", "Location 2", 25.0);
        createOrganizerEvent("My Event 3", "Location 3", 0.0); // Free event

        // Fetch all events using repository
        final List<UserEvent>[] fetchedEvents = new List[]{null};
        final Exception[] fetchError = new Exception[]{null};

        eventRepository.getAllEvents(new FirebaseEventRepository.EventListCallback() {
            @Override
            public void onEventsFetched(List<UserEvent> events) {
                fetchedEvents[0] = events;
            }

            @Override
            public void onError(Exception e) {
                fetchError[0] = e;
            }
        });

        // Wait for async operation to complete
        Thread.sleep(3000);

        assertNull("Should not have any errors", fetchError[0]);
        assertNotNull("Events should be fetched", fetchedEvents[0]);

        // Filter to only organizer's events (simulating what OHomeFrag does)
        List<UserEvent> myEvents = new ArrayList<>();
        for (UserEvent event : fetchedEvents[0]) {
            if (event.getOrganizerID() != null && event.getOrganizerID().equals(organizerUserId)) {
                myEvents.add(event);
            }
        }

        assertTrue("Should fetch at least 3 organizer events", myEvents.size() >= 3);

        // Verify all fetched events belong to the organizer
        for (UserEvent event : myEvents) {
            assertEquals("Event should belong to organizer", organizerUserId, event.getOrganizerID());
        }

        System.out.println("Fetch organizer's own events test passed - fetched " + myEvents.size() + " events");
    }

    /**
     * Test that only organizer's events are shown, not other users' events.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testFilterOnlyOrganizerEvents() throws Exception {
        // Create events owned by the organizer
        createOrganizerEvent("My Event 1", "My Location 1", 20.0);
        createOrganizerEvent("My Event 2", "My Location 2", 30.0);

        // Create another user and their events
        String otherUserId = createOtherUser();
        String otherEvent1Id = createEventForUser("Other Event 1", "Other Location 1", 40.0, otherUserId);
        String otherEvent2Id = createEventForUser("Other Event 2", "Other Location 2", 50.0, otherUserId);

        // Fetch all events
        QuerySnapshot snapshot = Tasks.await(
            db.collection("events").get(),
            10,
            TimeUnit.SECONDS
        );

        List<UserEvent> allEvents = new ArrayList<>();
        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            UserEvent event = doc.toObject(UserEvent.class);
            if (event != null) {
                event.setId(doc.getId());
                allEvents.add(event);
            }
        }

        // Filter to only organizer's events (simulating OHomeFrag logic)
        List<UserEvent> myEvents = new ArrayList<>();
        for (UserEvent event : allEvents) {
            if (event.getOrganizerID() != null && event.getOrganizerID().equals(organizerUserId)) {
                myEvents.add(event);
            }
        }

        // Verify only organizer's events are included
        assertTrue("Should have at least 2 organizer events", myEvents.size() >= 2);
        for (UserEvent event : myEvents) {
            assertEquals("All events should belong to organizer", organizerUserId, event.getOrganizerID());
            assertNotEquals("Should not contain other user's events", otherUserId, event.getOrganizerID());
        }

        // Clean up other user's events
        Tasks.await(db.collection("events").document(otherEvent1Id).delete(), 10, TimeUnit.SECONDS);
        Tasks.await(db.collection("events").document(otherEvent2Id).delete(), 10, TimeUnit.SECONDS);
        Tasks.await(db.collection("users").document(otherUserId).delete(), 10, TimeUnit.SECONDS);

        System.out.println("Filter only organizer's events test passed");
    }

    /**
     * Test fetching a specific organizer event by ID.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testFetchOrganizerEventById() throws Exception {
        // Create an event owned by the organizer
        String eventId = createOrganizerEvent("My Specific Event", "My Specific Location", 75.0);

        // Fetch the event by ID
        final UserEvent[] fetchedEvent = new UserEvent[]{null};
        final Exception[] fetchError = new Exception[]{null};

        eventRepository.fetchEventById(eventId, new FirebaseEventRepository.SingleEventCallback() {
            @Override
            public void onEventFetched(UserEvent event) {
                fetchedEvent[0] = event;
            }

            @Override
            public void onError(Exception e) {
                fetchError[0] = e;
            }
        });

        // Wait for async operation
        Thread.sleep(2000);

        assertNull("Should not have any errors", fetchError[0]);
        assertNotNull("Event should be fetched", fetchedEvent[0]);
        assertEquals("Event ID should match", eventId, fetchedEvent[0].getId());
        assertEquals("Event name should match", "My Specific Event", fetchedEvent[0].getName());
        assertEquals("Event location should match", "My Specific Location", fetchedEvent[0].getLocation());
        assertEquals("Event should belong to organizer", organizerUserId, fetchedEvent[0].getOrganizerID());

        System.out.println("Fetch organizer event by ID test passed");
    }

    /**
     * Test that organizer can fetch events with complete details.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testFetchOrganizerEventWithCompleteDetails() throws Exception {
        // Create event with all fields populated
        UserEvent organizerEvent = new UserEvent();
        organizerEvent.setName("Complete Organizer Event");
        organizerEvent.setLocation("Complete Location");
        organizerEvent.setDescr("Complete event description for organizer");
        organizerEvent.setPrice(150.0);
        organizerEvent.setCapacity(300);
        organizerEvent.setOrganizerID(organizerUserId);
        organizerEvent.setStartTimeMillis(System.currentTimeMillis() + 86400000L);
        organizerEvent.setEndTimeMillis(System.currentTimeMillis() + 172800000L);
        organizerEvent.setSelectionDateMillis(System.currentTimeMillis() + 43200000L);
        organizerEvent.setEntrantsToDraw(150);
        organizerEvent.setGeoRequired(true);
        organizerEvent.setImageUrl("https://res.cloudinary.com/dyb8t5n7k/image/upload/v1762484990/organizer_event.jpg");
        organizerEvent.setQrData("ORGANIZER_QR_123");

        String eventId = createEventInFirestore(organizerEvent);

        // Fetch and verify
        DocumentSnapshot snapshot = Tasks.await(
            db.collection("events").document(eventId).get(),
            10,
            TimeUnit.SECONDS
        );

        UserEvent fetchedEvent = snapshot.toObject(UserEvent.class);
        assertNotNull("Event should be fetched", fetchedEvent);

        assertEquals("Name should match", "Complete Organizer Event", fetchedEvent.getName());
        assertEquals("Location should match", "Complete Location", fetchedEvent.getLocation());
        assertEquals("Description should match", "Complete event description for organizer", fetchedEvent.getDescr());
        assertEquals("Price should match", 150.0, fetchedEvent.getPrice(), 0.01);
        assertEquals("Capacity should match", 300, fetchedEvent.getCapacity());
        assertEquals("Organizer ID should match", organizerUserId, fetchedEvent.getOrganizerID());
        assertEquals("Entrants to draw should match", 150, fetchedEvent.getEntrantsToDraw());
        assertTrue("Geo required should be true", fetchedEvent.isGeoRequired());
        assertEquals("Image URL should match", "https://res.cloudinary.com/dyb8t5n7k/image/upload/v1762484990/organizer_event.jpg", fetchedEvent.getImageUrl());
        assertEquals("QR data should match", "ORGANIZER_QR_123", fetchedEvent.getQrData());

        System.out.println("Fetch organizer event with complete details test passed");
    }

    /**
     * Test fetching multiple events created by organizer at different times.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testFetchMultipleOrganizerEventsWithDifferentDates() throws Exception {
        long now = System.currentTimeMillis();
        long oneDay = 86400000L;

        // Create events with different dates
        UserEvent pastEvent = new UserEvent();
        pastEvent.setName("Past Event");
        pastEvent.setLocation("Past Location");
        pastEvent.setDescr("Event that already happened");
        pastEvent.setPrice(20.0);
        pastEvent.setCapacity(50);
        pastEvent.setOrganizerID(organizerUserId);
        pastEvent.setStartTimeMillis(now - (oneDay * 2));
        pastEvent.setEndTimeMillis(now - oneDay);
        pastEvent.setSelectionDateMillis(now - (oneDay * 3));
        pastEvent.setEntrantsToDraw(25);
        pastEvent.setGeoRequired(false);
        String pastEventId = createEventInFirestore(pastEvent);

        UserEvent upcomingEvent = new UserEvent();
        upcomingEvent.setName("Upcoming Event");
        upcomingEvent.setLocation("Future Location");
        upcomingEvent.setDescr("Event happening soon");
        upcomingEvent.setPrice(35.0);
        upcomingEvent.setCapacity(100);
        upcomingEvent.setOrganizerID(organizerUserId);
        upcomingEvent.setStartTimeMillis(now + oneDay);
        upcomingEvent.setEndTimeMillis(now + (oneDay * 2));
        upcomingEvent.setSelectionDateMillis(now + (oneDay / 2));
        upcomingEvent.setEntrantsToDraw(50);
        upcomingEvent.setGeoRequired(true);
        String upcomingEventId = createEventInFirestore(upcomingEvent);

        // Fetch all events and filter to organizer's events
        QuerySnapshot snapshot = Tasks.await(
            db.collection("events").get(),
            10,
            TimeUnit.SECONDS
        );

        List<UserEvent> myEvents = new ArrayList<>();
        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            UserEvent event = doc.toObject(UserEvent.class);
            if (event != null && event.getOrganizerID() != null &&
                event.getOrganizerID().equals(organizerUserId)) {
                event.setId(doc.getId());
                myEvents.add(event);
            }
        }

        // Verify we have both events
        assertTrue("Should have at least 2 events", myEvents.size() >= 2);

        boolean foundPastEvent = false;
        boolean foundUpcomingEvent = false;

        for (UserEvent event : myEvents) {
            if (event.getName().equals("Past Event")) {
                foundPastEvent = true;
                assertTrue("Past event end time should be in the past", event.getEndTimeMillis() < now);
            }
            if (event.getName().equals("Upcoming Event")) {
                foundUpcomingEvent = true;
                assertTrue("Upcoming event start time should be in the future", event.getStartTimeMillis() > now);
            }
        }

        assertTrue("Should find past event", foundPastEvent);
        assertTrue("Should find upcoming event", foundUpcomingEvent);

        System.out.println("Fetch multiple organizer events with different dates test passed");
    }

    /**
     * Test fetching organizer events with waitlists.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testFetchOrganizerEventWithWaitlist() throws Exception {
        // Create event with waitlist
        UserEvent eventWithWaitlist = new UserEvent();
        eventWithWaitlist.setName("Popular Organizer Event");
        eventWithWaitlist.setLocation("Busy Location");
        eventWithWaitlist.setDescr("Event with many interested users");
        eventWithWaitlist.setPrice(45.0);
        eventWithWaitlist.setCapacity(100);
        eventWithWaitlist.setOrganizerID(organizerUserId);
        eventWithWaitlist.setStartTimeMillis(System.currentTimeMillis() + 86400000L);
        eventWithWaitlist.setEndTimeMillis(System.currentTimeMillis() + 172800000L);
        eventWithWaitlist.setSelectionDateMillis(System.currentTimeMillis() + 43200000L);
        eventWithWaitlist.setEntrantsToDraw(50);
        eventWithWaitlist.setGeoRequired(false);

        // Add users to waitlist
        List<String> waitlist = new ArrayList<>();
        waitlist.add("user1");
        waitlist.add("user2");
        waitlist.add("user3");
        waitlist.add("user4");
        waitlist.add("user5");
        eventWithWaitlist.setWaitlist(waitlist);

        String eventId = createEventInFirestore(eventWithWaitlist);

        // Fetch and verify waitlist
        DocumentSnapshot snapshot = Tasks.await(
            db.collection("events").document(eventId).get(),
            10,
            TimeUnit.SECONDS
        );

        UserEvent fetched = snapshot.toObject(UserEvent.class);
        assertNotNull("Event should be fetched", fetched);
        assertEquals("Event should belong to organizer", organizerUserId, fetched.getOrganizerID());
        assertNotNull("Waitlist should not be null", fetched.getWaitlist());
        assertEquals("Waitlist should have 5 users", 5, fetched.getWaitlist().size());
        assertTrue("Waitlist should contain user1", fetched.getWaitlist().contains("user1"));
        assertTrue("Waitlist should contain user5", fetched.getWaitlist().contains("user5"));

        System.out.println("Fetch organizer event with waitlist test passed");
    }

    /**
     * Test batch fetching multiple organizer events.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testBatchFetchOrganizerEvents() throws Exception {
        // Create 5 test events for the organizer
        List<String> eventIds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String eventId = createOrganizerEvent(
                "Organizer Event " + i,
                "Location " + i,
                i * 15.0
            );
            eventIds.add(eventId);
        }

        // Fetch all events using QuerySnapshot
        QuerySnapshot querySnapshot = Tasks.await(
            db.collection("events").get(),
            10,
            TimeUnit.SECONDS
        );

        List<UserEvent> myEvents = new ArrayList<>();
        for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
            UserEvent event = doc.toObject(UserEvent.class);
            if (event != null && event.getOrganizerID() != null &&
                event.getOrganizerID().equals(organizerUserId)) {
                event.setId(doc.getId());
                myEvents.add(event);
            }
        }

        // Verify we fetched at least our 5 events
        assertTrue("Should fetch at least 5 organizer events", myEvents.size() >= 5);

        // Verify our created events are in the fetched list
        int foundCount = 0;
        for (String eventId : eventIds) {
            for (UserEvent event : myEvents) {
                if (event.getId().equals(eventId)) {
                    foundCount++;
                    break;
                }
            }
        }

        assertEquals("All 5 created events should be fetched", 5, foundCount);

        System.out.println("Batch fetch organizer events test passed - fetched " + myEvents.size() + " events");
    }

    /**
     * Test handling empty event list when organizer has no events.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testNoEventsForNewOrganizer() throws Exception {
        // Don't create any events

        // Fetch all events and filter
        QuerySnapshot snapshot = Tasks.await(
            db.collection("events").get(),
            10,
            TimeUnit.SECONDS
        );

        List<UserEvent> myEvents = new ArrayList<>();
        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            UserEvent event = doc.toObject(UserEvent.class);
            if (event != null && event.getOrganizerID() != null &&
                event.getOrganizerID().equals(organizerUserId)) {
                event.setId(doc.getId());
                myEvents.add(event);
            }
        }

        assertEquals("New organizer should have 0 events", 0, myEvents.size());

        System.out.println("No events for new organizer test passed");
    }

    /**
     * Test fetching organizer events with different price ranges.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testFetchOrganizerEventsWithDifferentPrices() throws Exception {
        // Create events with various prices
        String freeEventId = createOrganizerEvent("Free Organizer Event", "Free Location", 0.0);
        String cheapEventId = createOrganizerEvent("Cheap Event", "Budget Location", 10.0);
        String expensiveEventId = createOrganizerEvent("Premium Event", "Luxury Location", 500.0);

        // Fetch events directly from Firestore
        DocumentSnapshot freeEvent = Tasks.await(
            db.collection("events").document(freeEventId).get(),
            10,
            TimeUnit.SECONDS
        );

        DocumentSnapshot cheapEvent = Tasks.await(
            db.collection("events").document(cheapEventId).get(),
            10,
            TimeUnit.SECONDS
        );

        DocumentSnapshot expensiveEvent = Tasks.await(
            db.collection("events").document(expensiveEventId).get(),
            10,
            TimeUnit.SECONDS
        );

        // Verify all events were fetched and belong to organizer
        UserEvent freeEventObj = freeEvent.toObject(UserEvent.class);
        UserEvent cheapEventObj = cheapEvent.toObject(UserEvent.class);
        UserEvent expensiveEventObj = expensiveEvent.toObject(UserEvent.class);

        assertEquals("Free event should belong to organizer", organizerUserId, freeEventObj.getOrganizerID());
        assertEquals("Cheap event should belong to organizer", organizerUserId, cheapEventObj.getOrganizerID());
        assertEquals("Expensive event should belong to organizer", organizerUserId, expensiveEventObj.getOrganizerID());

        assertEquals("Free event price should be 0", 0.0, freeEventObj.getPrice(), 0.01);
        assertEquals("Cheap event price should be 10", 10.0, cheapEventObj.getPrice(), 0.01);
        assertEquals("Expensive event price should be 500", 500.0, expensiveEventObj.getPrice(), 0.01);

        System.out.println("Fetch organizer events with different prices test passed");
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a test event owned by the organizer.
     *
     * @param name the event name
     * @param location the event location
     * @param price the event price
     * @return the Firestore document ID of the created event
     * @throws Exception if the creation fails
     */
    private String createOrganizerEvent(String name, String location, Double price) throws Exception {
        UserEvent event = new UserEvent();
        event.setName(name);
        event.setLocation(location);
        event.setDescr("Organizer event description");
        event.setPrice(price);
        event.setCapacity(100);
        event.setOrganizerID(organizerUserId);
        event.setStartTimeMillis(System.currentTimeMillis() + 86400000L);
        event.setEndTimeMillis(System.currentTimeMillis() + 172800000L);
        event.setSelectionDateMillis(System.currentTimeMillis() + 43200000L);
        event.setEntrantsToDraw(50);
        event.setGeoRequired(false);

        String eventId = createEventInFirestore(event);
        return eventId;
    }

    /**
     * Creates an event in Firestore and returns its ID.
     *
     * @param event the event to create
     * @return the Firestore document ID of the created event
     * @throws Exception if the creation fails
     */
    private String createEventInFirestore(UserEvent event) throws Exception {
        String id = db.collection("events").document().getId();
        event.setId(id);

        Tasks.await(
            db.collection("events").document(id).set(event),
            10,
            TimeUnit.SECONDS
        );

        createdEventIds.add(id);
        return id;
    }

    /**
     * Creates another test user and returns their user ID.
     *
     * @return the user ID of the created user
     * @throws Exception if user creation fails
     */
    private String createOtherUser() throws Exception {
        String otherEmail = "test_other_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        String otherPassword = "testPassword123";

        AuthResult authResult = Tasks.await(
            auth.createUserWithEmailAndPassword(otherEmail, otherPassword),
            30,
            TimeUnit.SECONDS
        );

        return authResult.getUser().getUid();
    }

    /**
     * Creates an event owned by a specific user.
     *
     * @param name the event name
     * @param location the event location
     * @param price the event price
     * @param organizerId the ID of the user who owns this event
     * @return the Firestore document ID of the created event
     * @throws Exception if the creation fails
     */
    private String createEventForUser(String name, String location, Double price, String organizerId) throws Exception {
        UserEvent event = new UserEvent();
        event.setName(name);
        event.setLocation(location);
        event.setDescr("Event for other user");
        event.setPrice(price);
        event.setCapacity(100);
        event.setOrganizerID(organizerId);
        event.setStartTimeMillis(System.currentTimeMillis() + 86400000L);
        event.setEndTimeMillis(System.currentTimeMillis() + 172800000L);
        event.setSelectionDateMillis(System.currentTimeMillis() + 43200000L);
        event.setEntrantsToDraw(50);
        event.setGeoRequired(false);

        String eventId = createEventInFirestore(event);
        return eventId;
    }
}

