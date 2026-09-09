-- =====================================================================================
-- Whether goods that came back went back on sale.
--
-- Not every return is resellable. "Changed my mind" comes back to the shelf; "arrived with a
-- cracked screen" comes back to the warehouse and never to a customer again. Guessing wrong in
-- either direction is expensive: sell a broken thing to the next person, or quietly write off
-- stock that was fine.
--
-- So it is a decision somebody makes when they open the box, recorded here. NULL means the
-- goods have not arrived yet and nobody has been asked.
-- =====================================================================================

ALTER TABLE return_requests ADD COLUMN restocked BOOLEAN;

COMMENT ON COLUMN return_requests.restocked IS
    'TRUE  - put back on sale, and a restock command was sent to Inventory Service.
     FALSE - received but written off; the units exist and are not for sale.
     NULL  - not received yet.';
