import sqlite3
import os

db_path = os.path.abspath(r'app/src/main/assets/inventory.db')
print(f"Modifying database at: {db_path}")

conn = sqlite3.connect(db_path)
cur = conn.cursor()

# 1. Execute DROP TABLE IF EXISTS reference_data;
print("\n--- 1. Dropping table reference_data ---")
cur.execute("DROP TABLE IF EXISTS reference_data;")
print("Executed: DROP TABLE IF EXISTS reference_data;")

# 2. Create users table and insert admin
print("\n--- 2. Creating users table and inserting admin ---")
cur.execute("""
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT,
    password TEXT
);
""")
print("Created table users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT, password TEXT).")

# Check if admin exists
cur.execute("SELECT id, username, password FROM users WHERE username = ?", ("admin",))
existing_admin = cur.fetchone()
if not existing_admin:
    cur.execute("INSERT INTO users (username, password) VALUES (?, ?)", ("admin", "admin123"))
    print("Inserted user: admin / admin123")
else:
    print(f"User admin already exists: {existing_admin}")

conn.commit()

# 3. Inspect the inventory_master table to confirm exact column names
print("\n--- 3. Inspecting inventory_master table ---")
cur.execute("PRAGMA table_info(inventory_master);")
columns = cur.fetchall()

print(f"{'cid':<4} | {'Column Name':<30} | {'Type':<10} | {'NotNull':<8} | {'Default':<10} | {'PK':<3}")
print("-" * 75)
for col in columns:
    cid, name, col_type, notnull, dflt_value, pk = col
    print(f"{cid:<4} | {name:<30} | {col_type:<10} | {notnull:<8} | {str(dflt_value):<10} | {pk:<3}")

# Check specific column names for Asset Type and Specific Location / Rack
asset_type_cols = [col[1] for col in columns if "asset" in col[1].lower() or "type" in col[1].lower()]
location_cols = [col[1] for col in columns if "location" in col[1].lower() or "rack" in col[1].lower()]

print("\nCandidate / matching columns:")
print(f"Asset Type columns: {asset_type_cols}")
print(f"Location / Rack columns: {location_cols}")

# Inspect existing users table content
print("\n--- Users table contents ---")
cur.execute("SELECT * FROM users;")
for u in cur.fetchall():
    print(u)

# Inspect all tables in inventory.db
print("\n--- Tables in inventory.db ---")
cur.execute("SELECT name FROM sqlite_master WHERE type='table';")
print([r[0] for r in cur.fetchall()])

# Sample row from inventory_master
print("\n--- Sample row from inventory_master ---")
cur.execute("SELECT * FROM inventory_master LIMIT 1;")
sample = cur.fetchone()
if sample:
    col_names = [col[1] for col in columns]
    for k, v in zip(col_names, sample):
        print(f"  {k}: {repr(v)}")

conn.close()
print("\nDone.")
