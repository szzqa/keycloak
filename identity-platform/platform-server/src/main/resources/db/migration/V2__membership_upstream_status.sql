-- Upstream status and local administrator approval are separate controls.
ALTER TABLE platform_membership ADD COLUMN upstream_active BOOLEAN NOT NULL DEFAULT true;
