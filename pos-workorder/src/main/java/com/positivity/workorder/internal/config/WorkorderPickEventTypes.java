package com.positivity.workorder.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

public final class WorkorderPickEventTypes {
  private WorkorderPickEventTypes() {
  }

  // Phase 3 events - pick execution
  public static final EventTypeRegistration WORKORDER_PICK_FACADE_RESOLVE_SCAN = EventTypeRegistration
      .write("WORKORDER_PICK_FACADE_RESOLVE_SCAN",
          "Resolve a barcode scan during mechanic picking workflow")
      .apiVersion("1").build();

  public static final EventTypeRegistration WORKORDER_PICK_FACADE_CONFIRM_LINE = EventTypeRegistration
      .write("WORKORDER_PICK_FACADE_CONFIRM_LINE", "Confirm a pick line quantity for a pick task")
      .apiVersion("1").build();

  public static final EventTypeRegistration WORKORDER_PICK_FACADE_COMPLETE_TASK = EventTypeRegistration
      .write("WORKORDER_PICK_FACADE_COMPLETE_TASK", "Complete a pick task for a workorder")
      .apiVersion("1").build();

  // Phase 4 event - consume
  public static final EventTypeRegistration WORKORDER_PICKED_ITEMS_CONSUME = EventTypeRegistration
      .write("WORKORDER_PICKED_ITEMS_CONSUME", "Consume picked items into a workorder")
      .apiVersion("1").build();

  public static final List<EventTypeRegistration> ALL_EVENT_TYPES = List.of(
      WORKORDER_PICK_FACADE_RESOLVE_SCAN,
      WORKORDER_PICK_FACADE_CONFIRM_LINE,
      WORKORDER_PICK_FACADE_COMPLETE_TASK,
      WORKORDER_PICKED_ITEMS_CONSUME);
}