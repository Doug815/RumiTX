CREATE INDEX `records_kind_owner_created` ON `records` (`kind`,`owner`,`created`);--> statement-breakpoint
CREATE INDEX `records_owner_created` ON `records` (`owner`,`created`);