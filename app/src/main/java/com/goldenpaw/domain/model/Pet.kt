package com.goldenpaw.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.Period

enum class Species(val label: String, val emoji: String) {
    DOG("Dog", "🐶"),
    CAT("Cat", "🐱");

    companion object {
        fun from(value: String): Species = entries.firstOrNull { it.name == value } ?: DOG
    }
}

enum class PetSex(val label: String) {
    UNKNOWN("Not set"), FEMALE("Female"), MALE("Male");

    companion object {
        fun from(value: String): PetSex = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

data class Pet(
    val id: String,
    val name: String,
    val species: Species,
    val breed: String,
    val sex: PetSex,
    val birthDate: LocalDate?,
    val photoPath: String?,
    val conditions: List<String>,
    val vetName: String,
    val vetPhone: String,
    val notes: String,
    val createdAt: Instant,
    val archivedAt: Instant?,
) {
    val isArchived: Boolean get() = archivedAt != null

    fun ageLabel(today: LocalDate = LocalDate.now()): String? {
        val birth = birthDate ?: return null
        val period = Period.between(birth, today)
        return when {
            period.years >= 1 -> if (period.months > 0 && period.years < 3) {
                "${period.years}y ${period.months}m"
            } else {
                "${period.years} ${if (period.years == 1) "year" else "years"}"
            }
            period.months >= 1 -> "${period.months} mo"
            else -> "${period.days} days"
        }
    }

    /** Dogs 7+ and cats 10+ are generally considered senior. */
    fun isSenior(today: LocalDate = LocalDate.now()): Boolean {
        val birth = birthDate ?: return false
        val years = Period.between(birth, today).years
        return when (species) {
            Species.DOG -> years >= 7
            Species.CAT -> years >= 10
        }
    }
}

object PetCatalog {
    val commonConditions = listOf(
        "Arthritis", "Kidney disease", "Diabetes", "Heart disease", "Cognitive decline",
        "Hyperthyroidism", "Dental disease", "Vision loss", "Hearing loss", "Cancer",
        "Liver disease", "Incontinence", "Hip dysplasia", "Allergies", "Obesity", "Seizures",
    )

    val dogBreeds = listOf(
        "Mixed breed", "Labrador Retriever", "Golden Retriever", "German Shepherd", "Beagle",
        "Bulldog", "French Bulldog", "Poodle", "Rottweiler", "Dachshund", "Yorkshire Terrier",
        "Boxer", "Shih Tzu", "Siberian Husky", "Cavalier King Charles Spaniel", "Chihuahua",
        "Pomeranian", "Border Collie", "Australian Shepherd", "Cocker Spaniel", "Doberman Pinscher",
        "Great Dane", "Pug", "Maltese", "Miniature Schnauzer", "Shetland Sheepdog",
        "Bernese Mountain Dog", "Boston Terrier", "Havanese", "Jack Russell Terrier",
        "Indian Pariah (Indie)", "Rajapalayam", "Chippiparai", "Kombai", "Mudhol Hound",
        "Labradoodle", "Greyhound", "Whippet", "Saint Bernard", "Newfoundland", "Bichon Frise",
        "Lhasa Apso", "Weimaraner", "Vizsla", "Akita", "Shiba Inu", "Collie", "Corgi",
    )

    val catBreeds = listOf(
        "Domestic Shorthair", "Domestic Longhair", "Mixed breed", "Persian", "Maine Coon",
        "Siamese", "Ragdoll", "Bengal", "British Shorthair", "Sphynx", "Abyssinian",
        "Scottish Fold", "Russian Blue", "Burmese", "Birman", "Norwegian Forest Cat",
        "Oriental Shorthair", "Devon Rex", "Cornish Rex", "Himalayan", "Exotic Shorthair",
        "Tonkinese", "Turkish Angora", "Savannah", "American Shorthair",
    )

    fun breedsFor(species: Species) = if (species == Species.DOG) dogBreeds else catBreeds
}
