# Snapshots

Why an order never reads the catalogue, and exactly what it copies instead.

---

## 1. The rule

> **An order is a historical record. Nothing on it may change after it is placed.**

That sounds obvious and is easy to break by accident. The tempting design is to store a product id
on each line and read the name, image and price back when the order is displayed — it normalises
nicely, it avoids duplication, and it is wrong. Under it:

- Renaming a product renames it on every invoice ever issued.
- Correcting a price rewrites what past customers appear to have agreed to pay.
- Re-shooting a photo changes what an order from last year looks like.
- Deleting a product makes an order unrenderable.

None of those raise an error. The order still loads; it just quietly says something untrue, and
the first person to notice is usually a customer holding a receipt that disagrees.

So the order copies. Every field it needs to describe itself is taken once, at the moment it is
placed, and never looked up again.

---

## 2. What is copied

### On each line — `order_items`

| Column | Why it is copied |
|---|---|
| `product_id` | The only field that *is* a reference. Used for "buy this again", never for display |
| `sku` | What warehouses and invoices quote |
| `product_name` | The name the customer read |
| `product_category` | Where they found it; the catalogue may have reorganised since |
| `product_image_url` | The picture they saw. Products get re-shot and replaced |
| `quantity` | |
| `list_price` | Catalogue price at that moment, before any reduction |
| `discount_amount` | Per unit reduction |
| `unit_price` | What was actually charged per unit |
| `subtotal` | The line total |

### On the order — `orders`

| Column | Why |
|---|---|
| `currency` | Frozen; the platform refuses to mix currencies in one order |
| `subtotal_amount` | Sum of the lines at list price |
| `discount_total` | What came off |
| `total_amount` | What the customer was charged |
| `shipping_address` | As typed, not as the address book holds it now |

`product_id` is deliberately the exception. It is a link, not a description: the product page it
points at is *allowed* to have changed, because it is the current product rather than a claim
about the past. Everything shown on the order itself comes from the copy.

---

## 3. The arithmetic

Two invariants, held in the entity rather than trusted from callers, because a line whose numbers
do not add up is an invoice nobody can defend — and nothing else in the system would notice.

```
unit_price      = list_price - discount_amount
subtotal        = unit_price * quantity

subtotal_amount = Σ (list_price * quantity)
discount_total  = Σ (discount_amount * quantity)
total_amount    = subtotal_amount - discount_total
```

`OrderItem.recalculateSubtotal()` and `Order.recalculateTotals()` are the only places these are
computed. `OrderCommandServiceTest` pins all three.

---

## 4. Promotions

**There is no promotion engine yet.** `discount_amount` and `discount_total` are structurally zero,
and `list_price` always equals `unit_price`.

The columns exist anyway, and that is a deliberate call rather than speculative plumbing. A
discount is only meaningful next to the price it applied to: an order line recording `unit_price =
1519.20` with no other context is a number nobody can account for six months later. Recording that
it listed at `1899.00` with `379.80` off makes the same line self-explanatory forever.

Building the shape now also means the promotion work (**C5**) only has to populate it, rather than
migrate every historical order into a new one.

What C5 still has to add, because it genuinely cannot exist yet:

- **Which** promotion applied — a code, a campaign id, the rule that matched. That belongs in its
  own table keyed by order, not squeezed into these columns.
- Order-level discounts that are not attributable to a single line (free shipping, "£10 off your
  basket"). `discount_total` is currently the sum of the lines; it will need to stop being derived.

The UI already handles a non-zero discount: the line shows the list price struck through, and the
totals grow a subtotal and discount row. Those branches are dead today and will light up the day
a promotion applies, rather than needing to be written then.

---

## 5. Tax and shipping

Also absent (**C4**), and they are not the same shape as a discount. Tax is calculated *from* the
totals rather than being part of them, and shipping is a charge rather than a reduction. When they
arrive they need their own columns — `tax_amount`, `shipping_amount`, and a `tax_rate` snapshot,
because the rate that applied on the day is exactly the kind of thing that changes and exactly the
kind of thing an auditor asks about.

Nothing here pretends they exist. `total_amount` today means "the sum of the goods", and it is the
number the customer is charged because there is nothing else to charge them for.

---

## 6. Where else this rule applies

**The saga.** Commands carry the values the participant needs rather than an id to look up:
`PROCESS_PAYMENT` carries the amount, `RESERVE_INVENTORY` carries the lines. A participant that
has to call back is a participant that can be blocked by the service it calls — and one that looks
up a price mid-saga could charge a different number from the one the customer agreed to.

**The read model.** `order_read_model.items_json` is a copy of the copy, regenerated from the
aggregate. It is derived data and can be rebuilt at any time, which is what makes it safe to
denormalise.

**Payments.** The payment row stores its own `amount` rather than reading the order's, for the
same reason: a charge is a record of money that moved, not a view onto something that might move
again.

**Notifications.** Rendered at send time and stored as text. An email you can no longer reproduce
is not an audit trail.
