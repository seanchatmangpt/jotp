package io.github.seanchatmangpt.jotp.demo;

/**
 * Value type representing a person entity.
 *
 * <p>Example demonstrating domain modeling with records in JOTP applications. Person objects are
 * immutable and validated at construction time.
 */
public record Person(String firstName, String lastName, String email, Address address) {

    /** Compact constructor — validates all inputs. */
    public Person {
        if (firstName == null || firstName.isBlank()) {
            throw new IllegalArgumentException("First name cannot be blank");
        }
        if (lastName == null || lastName.isBlank()) {
            throw new IllegalArgumentException("Last name cannot be blank");
        }
    }

    /** Creates a new person with minimal required fields. */
    public static Person of(String firstName, String lastName) {
        return new Person(firstName, lastName, null, null);
    }

    /** Returns the full name of this person. */
    public String fullName() {
        return firstName + " " + lastName;
    }
}
