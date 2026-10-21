from faker import Faker
import random
import csv
import logging
import os

# Logging configuration
logging.basicConfig(level=logging.DEBUG)

# Faker instance & seeds for reproducibility
fake = Faker()
Faker.seed(42)
random.seed(42)

# Corresponds to roughly 1mb, 10mb, 100mb, and 1gb of data respectively
file_size_targets = {
    40_000: "1mb",
    400_000: "10mb",
    4_000_000: "100mb",
    40_000_000: "1000mb"
}

# If directory doesn't exist, create it
logging.debug("Creating directory 'generated_data' if it doesn't exist...")
os.makedirs("generated_data", exist_ok=True)
logging.debug("Directory 'generated_data' is ready.")

# Sample list of cities to speed of data generation
logging.debug("Generating a list of 15,000 random cities...")
cities = [fake.city() for _ in range(15_000)]
logging.debug("List of cities generated.")

# Generates a CSV file with random `n` number of lines of data for cities, distances, and prices


def generate_data(n, size):
    with open(f"generated_data/data_{size}.csv", mode='w', newline='') as file:
        logging.debug(f"Generating data for data_{size}.csv...")

        # remove default newline character to avoid extra blank lines in
        writer = csv.writer(file, lineterminator='')

        for i in range(n):
            if i > 0:
                file.write("\n")  # Add a newline before writing the next line

            # Select a random city from the pre-generated list
            city = random.choice(cities)
            distance = random.randint(1, 1000)  # Random distance in kilometers
            price = round(random.uniform(10.0, 1000.0),
                          2)  # Random price in dollars

            writer.writerow([city, distance, price])

        logging.debug("Data generation complete.")


# Generate data for each target size
for target_size in file_size_targets:
    generate_data(target_size, file_size_targets.get(target_size))
