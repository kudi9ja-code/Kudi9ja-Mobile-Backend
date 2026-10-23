-- The agreed management fee: ₦5,000 flat up to ₦250,000, 2% of the whole loan
-- from ₦250,001. The defaults changed, but settings saved before still carried
-- the old ₦500,000 threshold (and V21 copied it into the group terms).
update platform_settings set processing_fee_threshold = 250000 where processing_fee_threshold = 500000;
update platform_settings set group_processing_fee_threshold = 250000 where group_processing_fee_threshold = 500000;
