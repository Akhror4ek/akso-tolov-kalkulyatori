# WASYT seller workflow — observed API contract

This document records the WASYT seller requests observed in the AKSO Finance integration investigation. It is based on successful browser requests and responses captured by the project owner on 2026-11-11. The product search endpoint and response shape are based on the project owner's successful WASYT seller Network capture from 2026-11-12.

## Authentication and safety

- API host: `https://api.wasyt.uz`
- Web origin observed: `https://app.wasyt.uz`
- Authenticated requests use an `Authorization: Bearer <token>` header.
- The token must be obtained through the existing WASYT account-link flow and kept in the existing encrypted local secure store. Never hard-code, log, upload, or send a token to Firebase.
- A seller-role HTTP 403 is a permission failure. Show the error to the user; do not attempt to bypass WASYT role checks.

## Observed endpoints

### 1. Search products in the selected seller warehouse

`GET /common/public/records/product`

This is the exact endpoint captured from the WASYT seller product picker on 2026-11-12. The request returned HTTP 200 for seller account company `2762`, selected warehouse `487`, and search term `sandiq`.

Required query parameters:
- `filter[stock_id]=<selected stock id>`
- `filter[company_id]=<active company id>`
- `filter[has_price]=1`
- `filter[with_reserved_quantity]=1`
- `filter[stock_quantity][]=0.0001`
- `filter[name_or_barcode]=<product name or barcode>`
- `page=1`
- `page-size=20`

Example request:
```
GET https://api.wasyt.uz/common/public/records/product?filter[stock_id]=487&filter[company_id]=2762&filter[has_price]=1&filter[with_reserved_quantity]=1&filter[stock_quantity][]=0.0001&filter[name_or_barcode]=sandiq&page=1&page-size=20
```

The successful response uses flat product records in `data[]`, not a nested `product` object. Relevant fields:
- `id`: product ID to send in `products[n][id]`
- `value`: product display name
- `barcode`, `additional_barcodes[]`, `industrial_barcode`
- `price.price` and `price.currency`
- `stock_product.quantity`
- `stock_product.reserved_quantity`

Available stock for display is `stock_product.quantity - stock_product.reserved_quantity`, floored at zero. The captured UZS price is read from `price.price`; do not use purchase-cost fields as a substitute.

The previous Android attempts used `/company/stock/product/catalog` and then `/company/stock/product`. Neither matches the seller UI's captured successful request. Android seller product search must use `/common/public/records/product` with the filters above. Never work around a 403 by impersonating an administrator or using another user's token.

### 2. Search clients

`GET /company/client/client?filter[common]=<query>&page=1&page-size=3`

Observed response: HTTP 200 with `data` array. Client records include fields such as `id`, `full_name`, `main_phone`, `gender`, `birth_date`, `additional_phones`, `languages`, `socials`, and `is_deleted`.

### 3. Create client

`POST /company/client/client/`

Observed as `multipart/form-data`. Captured fields:
- `full_name`
- `gender` (`male` or `female`)
- `main_phone`
- `source_id`
- `languages[]`
- `socials[telegram]`
- `socials[instagram]`
- `socials[facebook]`
- `socials[other]`
- `image`
- `image_old_keys` (JSON array string; use `[]` when no previous image keys exist; omit `image` entirely when no image file is selected)

Observed response: HTTP 200 and a created client object containing a new `id`, `company_id`, `creator_id`, `full_name`, and other client fields. The seller form requires `image_old_keys` as a JSON array. Send the literal JSON string `[]` when no previous image keys exist. Do not send an empty text value or use a blank `image_old_keys[]` multipart field: WASYT validates this as JSON and returns `image_old_keys: Rasm JSON qatori bo‘lishi kerak.` If no photo is selected, omit the `image` part entirely; sending `image` as an empty string causes `image: Rasm fayl bo‘lishi kerak.`

### 4. Create seller cart

`POST /company/trade/seller-cart`

Observed multipart fields:
- `company_id`
- `stock_id`
- `currency` (observed value: `uzs`)
- `is_reserved` (observed values: `1` or `0`)
- `products[0][id]`
- `products[0][quantity]`
- `products[0][amount]`
- `products[0][discount]`

A successful response returned a cart object including `id`, `code`, `status` (`picking` in the captured example), `amount`, `total_amount`, `products_count`, and `is_reserved`.

### 5. Update cart, attach client and apply discount

`POST /company/trade/seller-cart/{cart_id}?_method=PUT`

The request is a POST with Laravel-style method override `_method=PUT`. Observed multipart fields include:
- `_method=PUT`
- `company_id`
- `stock_id`
- `client_id`
- `currency`
- `is_reserved`
- `discount_mode`
- `discount_value`
- `products[n][id]`
- `products[n][quantity]`
- `products[n][amount]`
- `products[n][discount]`

Observed discount modes:
- `percent`: `discount_value` is a percentage. Example: 1% of 1,250,000 UZS produced a 12,500 UZS discount and 1,237,500 UZS total.
- `integer`: `discount_value` is a fixed UZS discount. Example: 50,000 UZS off 1,250,000 UZS produced a 1,200,000 UZS total.

The server-calculated fields included `discount_amount` and `total_amount`. The client should display server-returned totals after a successful update.

### 6. Send cart to cashier

`POST /company/trade/seller-cart/{cart_id}/send?_method=PATCH`

Observed as POST with `_method=PATCH`. A successful response returned the cart with `status: "sent"` and a populated `sent_at`.

### 7. List seller carts

`GET /company/trade/seller-cart?page-size=20&page=1&with[]=client&with[]=creator.profile&sort[]=-created_at`

Observed HTTP 200 with a paginated `data` array and `meta` pagination information. Captured cart records included `id`, `code`, `client_id`, `sale_id`, `status`, `sale_status`, `amount`, `discount_amount`, `total_amount`, `products_count`, `created_at`, `updated_at`, `sent_at`, and cancellation fields.

### 8. Cancel a picking cart

`POST /company/trade/seller-cart/{cart_id}/cancel?_method=PATCH`

Observed request used POST with `_method=PATCH` and no additional form fields beyond the method override. One successful response was `{"message":"Success"}`.

Cancellation is state-dependent. An attempt to change a cart already sent to the cashier was rejected with a message that the cart had already been sent and could not be changed. Never retry or cancel another real cart as a test.

## Implementation rules

1. Keep WASYT operations in a dedicated seller module; do not mix them into the existing installment calculator.
2. Use the already linked seller account and encrypted token store. Never ask the user to paste credentials or tokens into chat.
3. Do not assume admin endpoints or try to elevate seller permissions.
4. Use server-returned client IDs, cart IDs, status, discount and total values.
5. Keep write operations behind explicit user actions and confirmation screens.
6. Treat API response structures not captured in full as unknown until verified. In particular, complete catalogue product field mapping, stock selection, cashier-side sale completion, and detailed cancellation reasons still need verification.
7. Do not modify existing Firebase data models or calculator behavior as part of the seller integration unless a separate migration is designed and tested.
