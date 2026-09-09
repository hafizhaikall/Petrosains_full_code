import sqlite3
import re
import os

db_path = r'c:\Projects\app\src\main\assets\inventory.db'

print(f"Opening database at: {db_path}")
conn = sqlite3.connect(db_path)
cur = conn.cursor()

# -------------------------------------------------------------
# 1) Find and remove all #VALUE! strings
# -------------------------------------------------------------
print("\n--- 1) Removing #VALUE! strings ---")
tables = [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name != 'sqlite_sequence'").fetchall()]

total_value_removed = 0
for table in tables:
    cur.execute(f'PRAGMA table_info("{table}")')
    cols = [c[1] for c in cur.fetchall()]
    for col in cols:
        # Find rows where col contains #VALUE!
        rows = cur.execute(f'SELECT rowid, "{col}" FROM "{table}" WHERE "{col}" LIKE "%#VALUE!%"').fetchall()
        for rowid, val in rows:
            new_val = str(val).replace('#VALUE!', '').strip()
            cur.execute(f'UPDATE "{table}" SET "{col}" = ? WHERE rowid = ?', (new_val, rowid))
            total_value_removed += 1
            print(f"  [{table}] rowid={rowid}, col='{col}': removed #VALUE! -> {repr(new_val)}")

# In inventory_master, also clean up empty phantom rows (rows with no Item Code and no Item Name)
cur.execute('DELETE FROM "inventory_master" WHERE ("Item Code" IS NULL OR TRIM("Item Code") = "") AND ("Item Name" IS NULL OR TRIM("Item Name") = "")')
deleted_empty = cur.rowcount
print(f"Removed #VALUE! occurrences: {total_value_removed}")
print(f"Deleted {deleted_empty} empty phantom rows from inventory_master")


# -------------------------------------------------------------
# 2) Fix malformed dates in date columns
# -------------------------------------------------------------
print("\n--- 2) Fixing malformed dates ---")
date_cols = ["Last Stocktake Date", "Items Out (Date)", "Items In (Date)"]

def fix_date_val(date_str):
    if not date_str or not isinstance(date_str, str):
        return date_str
    s = date_str.strip()
    if not s:
        return s

    # Fix multiple slashes: e.g. 24//4/26 -> 24/4/26
    s = re.sub(r'/+', '/', s)

    # Fix missing slash between month and year 26:
    # e.g., 7/726 -> 7/7/26, 6/626 -> 6/6/26
    m = re.match(r'^(\d{1,2})/(\d{1,2})(26)$', s)
    if m:
        s = f"{m.group(1)}/{m.group(2)}/{m.group(3)}"

    # Fix if missing slash with 2026: e.g. 7/72026 -> 7/7/26
    m4 = re.match(r'^(\d{1,2})/(\d{1,2})(2026)$', s)
    if m4:
        s = f"{m4.group(1)}/{m4.group(2)}/26"

    # Fix truncated year: e.g. 13/7/2 -> 13/7/26
    m_trunc = re.match(r'^(\d{1,2})/(\d{1,2})/2$', s)
    if m_trunc:
        s = f"{m_trunc.group(1)}/{m_trunc.group(2)}/26"

    # Fix typo year: e.g. 11/7/2006 -> 11/7/26
    if s.endswith('/2006'):
        s = s[:-5] + '/26'

    # Normalize 4-digit 2026 to 26: e.g. 3/2/2026 -> 3/2/26
    if s.endswith('/2026'):
        s = s[:-5] + '/26'

    return s

fixed_date_count = 0
for col in date_cols:
    rows = cur.execute(f'SELECT rowid, "Item Code", "{col}" FROM "inventory_master" WHERE "{col}" IS NOT NULL AND "{col}" != ""').fetchall()
    for rowid, code, val in rows:
        fixed = fix_date_val(val)
        if fixed != val:
            cur.execute(f'UPDATE "inventory_master" SET "{col}" = ? WHERE rowid = ?', (fixed, rowid))
            fixed_date_count += 1
            print(f"  Fixed date in '{col}' for {code} (rowid={rowid}): {repr(val)} -> {repr(fixed)}")

print(f"Total date entries corrected: {fixed_date_count}")


# -------------------------------------------------------------
# 3) Change any 'Pending' status entries to 'Problem'
# -------------------------------------------------------------
print("\n--- 3) Updating 'Pending' status to 'Problem' ---")

# Update in inventory_master
cur.execute('UPDATE "inventory_master" SET "Status" = \'Problem\' WHERE "Status" = \'Pending\' OR LOWER("Status") = \'pending\'')
inv_status_updated = cur.rowcount

# Update in reference_data
cur.execute('UPDATE "reference_data" SET "Status" = \'Problem\' WHERE "Status" = \'Pending\' OR LOWER("Status") = \'pending\'')
ref_status_updated = cur.rowcount

# Check if 'Problem' exists in reference_data Status options; if not and there is an empty slot or needed, add it
ref_statuses = [r[0] for r in cur.execute('SELECT DISTINCT "Status" FROM "reference_data" WHERE "Status" IS NOT NULL AND "Status" != ""').fetchall()]
print(f"Current reference_data Status options: {ref_statuses}")
if 'Problem' not in ref_statuses:
    # Update first empty or append so the dropdown logic has 'Problem'
    empty_row = cur.execute('SELECT rowid FROM "reference_data" WHERE "Status" IS NULL OR "Status" = "" LIMIT 1').fetchone()
    if empty_row:
        cur.execute('UPDATE "reference_data" SET "Status" = \'Problem\' WHERE rowid = ?', (empty_row[0],))
        print(f"Added 'Problem' to reference_data Status options at rowid {empty_row[0]}")
    else:
        cur.execute('INSERT INTO "reference_data" ("Status") VALUES (\'Problem\')')
        print("Inserted 'Problem' into reference_data Status options")

print(f"Status updated in inventory_master: {inv_status_updated}")
print(f"Status updated in reference_data: {ref_status_updated}")


# -------------------------------------------------------------
# Commit, Vacuum, and Verify Integrity
# -------------------------------------------------------------
conn.commit()
print("\nRunning VACUUM to compact database...")
conn.execute("VACUUM")

integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
print(f"PRAGMA integrity_check: {integrity}")

# Final count verification
total_items = conn.execute('SELECT COUNT(*) FROM "inventory_master"').fetchone()[0]
print(f"Total valid items in inventory_master: {total_items}")

conn.close()
print("\nDatabase repair completed successfully!")
