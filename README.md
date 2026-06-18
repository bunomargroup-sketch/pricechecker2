# مجموعة بن عمر — Showroom Price & Cart App

A fast internal showroom web app for searching product prices, building customer carts, saving quotations, and syncing saved carts between devices using Supabase.

## Main file

```text
index.html
```

The app is currently a standalone HTML file with embedded product data and UI logic.

## Main features

- Fast product search by code, name, brand, or model
- Add extra search boxes with `+`
- Product price card
- Add products to cart
- Select quantity before adding
- Edit price in cart
- Apply discount percentage
- Add customer name, phone, and notes
- Save carts
- Sync saved carts with Supabase
- User login by identifier + code, no email required
- Open saved carts on phone and PC with the same identifier/code
- Cart history
- Product search history
- WhatsApp share
- Print / Save as PDF quotation
- Internal subtle margin display

## Deployment

The app is deployed by uploading `index.html` to GitHub Pages.

Update process:

1. Replace the old `index.html` in the GitHub repository.
2. Commit changes.
3. Wait 1–3 minutes.
4. Open the GitHub Pages link.
5. Refresh browser if the old version is cached.

## Supabase

Supabase is used for syncing saved carts.

Project URL:

```text
https://kkqbkumobeimwuscxztu.supabase.co
```

Do not publish private Supabase credentials such as:

- database password
- service_role key

## Database tables

The app uses these main tables:

```text
app_users
carts
cart_items
```

`app_users` is used for identifier/code login.

`carts` stores saved cart headers.

`cart_items` stores the products inside saved carts.

## Product data

Current product columns used by the app:

```text
code
name
brand
model
price
cost
```

Cost is used only for internal margin calculations.

## Future plan

A separate client-facing ordering website may be added later for selected top-selling products.

Suggested first version:

- around 50 selected products
- product photos
- product search
- client cart
- client order form
- orders saved to Supabase
- staff app page for website orders

## Backup recommendation

Keep backups of:

```text
index.html
latest products CSV
logo.png
workflow.md
README.md
```
