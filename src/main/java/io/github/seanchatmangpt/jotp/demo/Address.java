package io.github.seanchatmangpt.jotp.demo;

/**
 * Value type representing a postal address.
 *
 * <p>Example demonstrating value type pattern in JOTP applications. Address objects are immutable
 * and validated at construction time.
 */
public record Address(String street, String city, String state, String postalCode, String country) {

    /** Compact constructor — validates all inputs. */
    public Address {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("City cannot be blank");
        }
        if (postalCode == null || postalCode.isBlank()) {
            throw new IllegalArgumentException("Postal code cannot be blank");
        }
    }

    /** Creates a new address with minimal required fields. */
    public static Address of(String city, String postalCode) {
        return new Address(null, city, null, postalCode, null);
    }

    /** Creates a new US address. */
    public static Address us(String street, String city, String state, String postalCode) {
        return new Address(street, city, state, postalCode, "USA");
    }
}
