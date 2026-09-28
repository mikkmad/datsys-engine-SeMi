CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'src/main/resources/trips.csv';
SELECT * FROM trips;
SELECT * FROM trips WHERE city = 'Odense';