CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'generated_data/data_1mb.csv';