-- Optional date selected by the task owner; no background messages or model calls.
ALTER TABLE im_personal_task ADD COLUMN due_date DATE NULL;
