SELECT * FROM orders, customers, products
WHERE o_custname = c_name AND o_prodname = p_name;
