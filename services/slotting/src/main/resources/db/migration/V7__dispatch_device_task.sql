-- Execution layer (epic 1): when a plan is dispatched to flow-orchestrator as a physical move it
-- records the created device-task id, both for traceability and so a dispatched plan (status moved
-- off RECOMMENDED / PLANNED) is never dispatched again.
ALTER TABLE reslot_recommendation ADD COLUMN device_task_id uuid;
ALTER TABLE replenishment_task    ADD COLUMN device_task_id uuid;
