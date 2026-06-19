-- Issue #711: on-behalf timer-start audit for workorder labor entries.
-- When a privileged actor (workorder:labor:add_on_behalf) starts a timer for a
-- technician other than themselves or the current assignee, persist the
-- justification and the initiating actor so the divergence is reconstructable.
-- Follows the same actor/subject/reason on-behalf audit approach as travel_segment
-- (here: acted_by_user_id holds the actor's stable UUID, created_by the actor username).
ALTER TABLE workorder_labor_entry ADD COLUMN on_behalf_reason text;
ALTER TABLE workorder_labor_entry ADD COLUMN acted_by_user_id uuid;
