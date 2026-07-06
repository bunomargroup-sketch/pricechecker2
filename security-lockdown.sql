-- Security Lockdown for Ben Omar Suite
-- Recreates RLS policies with tight, correct permissions.

-- 1. Enable RLS on all tables
ALTER TABLE app_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE web_products ENABLE ROW LEVEL SECURITY;
ALTER TABLE web_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE web_order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE carts ENABLE ROW LEVEL SECURITY;
ALTER TABLE cart_items ENABLE ROW LEVEL SECURITY;

-- If pos tables exist in the schema, enable RLS on them dynamically or directly:
-- ALTER TABLE pos_suppliers ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE pos_ledgers ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE pos_payments ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE pos_stock ENABLE ROW LEVEL SECURITY;

-- 2. Drop existing policies (idempotent helper to start clean)
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT policyname, tablename 
        FROM pg_policies 
        WHERE schemaname = 'public' 
          AND tablename IN ('app_users', 'web_products', 'web_orders', 'web_order_items', 'carts', 'cart_items', 'product_costs', 'staff_roles')
    ) LOOP
        EXECUTE 'DROP POLICY IF EXISTS ' || quote_ident(r.policyname) || ' ON ' || quote_ident(r.tablename);
    END LOOP;
END $$;

-- 3. Staff Roles Table & Policies
CREATE TABLE IF NOT EXISTS staff_roles (
    user_id uuid PRIMARY KEY REFERENCES auth.users ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('admin', 'seller'))
);
ALTER TABLE staff_roles ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow users to view own staff role" ON staff_roles
    FOR SELECT TO authenticated USING (auth.uid() = user_id);

-- 4. Product Costs Table & Policies
CREATE TABLE IF NOT EXISTS product_costs (
    code text PRIMARY KEY,
    cost numeric NOT NULL
);
ALTER TABLE product_costs ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow authenticated staff to view costs" ON product_costs
    FOR SELECT TO authenticated USING (true);

-- 5. Web Products Policies
-- Active column in schema is boolean 'active'
CREATE POLICY "Allow public active products read" ON web_products
    FOR SELECT TO anon, authenticated USING (active = true);

CREATE POLICY "Allow authenticated staff web_products full access" ON web_products
    FOR ALL TO authenticated USING (true) WITH CHECK (true);

-- 6. Web Orders & Web Order Items Policies
CREATE POLICY "Allow public order submissions" ON web_orders
    FOR INSERT TO anon WITH CHECK (status = 'new');

CREATE POLICY "Allow public order items submissions" ON web_order_items
    FOR INSERT TO anon WITH CHECK (true);

CREATE POLICY "Allow authenticated staff web_orders read/update" ON web_orders
    FOR SELECT, UPDATE TO authenticated USING (true);

CREATE POLICY "Allow authenticated staff web_order_items read" ON web_order_items
    FOR SELECT TO authenticated USING (true);

-- 7. Carts & Cart Items (with user_id reference)
-- Ensure user_id column exists
ALTER TABLE carts ADD COLUMN IF NOT EXISTS user_id uuid REFERENCES auth.users;
ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS user_id uuid REFERENCES auth.users;

CREATE POLICY "Allow authenticated users own carts access" ON carts
    FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Allow authenticated users own cart_items access" ON cart_items
    FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

-- 8. Storage bucket product-images policies
-- Storage policies are applied to storage.objects
DO $$
BEGIN
    DROP POLICY IF EXISTS "Public Read Product Images" ON storage.objects;
    DROP POLICY IF EXISTS "Staff Write Product Images" ON storage.objects;
EXCEPTION
    WHEN others THEN NULL;
END $$;

CREATE POLICY "Public Read Product Images" ON storage.objects
    FOR SELECT TO public USING (bucket_id = 'product-images');

CREATE POLICY "Staff Write Product Images" ON storage.objects
    FOR ALL TO authenticated USING (bucket_id = 'product-images') WITH CHECK (bucket_id = 'product-images');

-- Verification Query to run in Supabase SQL editor
SELECT schemaname, tablename, policyname, roles, cmd, qual, with_check 
FROM pg_policies 
WHERE schemaname IN ('public', 'storage');
