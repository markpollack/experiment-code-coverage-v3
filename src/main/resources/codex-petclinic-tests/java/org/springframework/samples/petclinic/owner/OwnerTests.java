/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.samples.petclinic.owner;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Test class for {@link Owner}
 */
class OwnerTests {

	private Owner buildOwner() {
		Owner owner = new Owner();
		owner.setId(1);
		owner.setFirstName("George");
		owner.setLastName("Franklin");
		owner.setAddress("110 W. Liberty St.");
		owner.setCity("Madison");
		owner.setTelephone("6085551023");
		return owner;
	}

	private Pet buildPet(String name, int id) {
		Pet pet = new Pet();
		pet.setName(name);
		PetType dog = new PetType();
		dog.setName("dog");
		pet.setType(dog);
		pet.setBirthDate(LocalDate.of(2010, 1, 1));
		return pet;
	}

	@Test
	void testAddPetOnlyAddsNewPets() {
		Owner owner = buildOwner();
		Pet existingPet = buildPet("Buddy", 1);
		existingPet.setId(1);
		owner.addPet(existingPet);
		assertThat(owner.getPets()).isEmpty();
	}

	@Test
	void testAddPetAddsNewPet() {
		Owner owner = buildOwner();
		Pet newPet = buildPet("Buddy", 0);
		owner.addPet(newPet);
		assertThat(owner.getPets()).hasSize(1);
	}

	@Test
	void testGetPetByIdFound() {
		Owner owner = buildOwner();
		Pet pet = buildPet("Max", 0);
		owner.addPet(pet);
		pet.setId(5);
		assertThat(owner.getPet(5)).isEqualTo(pet);
	}

	@Test
	void testGetPetByIdNotFound() {
		Owner owner = buildOwner();
		assertThat(owner.getPet(99)).isNull();
	}

	@Test
	void testGetPetByIdSkipsNewPets() {
		Owner owner = buildOwner();
		Pet newPet = buildPet("Max", 0);
		owner.addPet(newPet);
		assertThat(owner.getPet((Integer) null)).isNull();
	}

	@Test
	void testGetPetByNameCaseInsensitive() {
		Owner owner = buildOwner();
		Pet pet = buildPet("Max", 0);
		owner.addPet(pet);
		pet.setId(5);
		assertThat(owner.getPet("max")).isEqualTo(pet);
		assertThat(owner.getPet("MAX")).isEqualTo(pet);
	}

	@Test
	void testGetPetByNameNotFound() {
		Owner owner = buildOwner();
		assertThat(owner.getPet("Unknown")).isNull();
	}

	@Test
	void testGetPetByNameIgnoreNewTrue() {
		Owner owner = buildOwner();
		Pet newPet = buildPet("Max", 0);
		owner.addPet(newPet);
		assertThat(owner.getPet("Max", true)).isNull();
	}

	@Test
	void testGetPetByNameIgnoreNewFalse() {
		Owner owner = buildOwner();
		Pet newPet = buildPet("Max", 0);
		owner.addPet(newPet);
		assertThat(owner.getPet("Max", false)).isEqualTo(newPet);
	}

	@Test
	void testGetPetByNameIgnoreNewFalseReturnsExistingPet() {
		Owner owner = buildOwner();
		Pet pet = buildPet("Max", 0);
		owner.addPet(pet);
		pet.setId(5);
		assertThat(owner.getPet("Max", false)).isEqualTo(pet);
	}

	@Test
	void testAddVisit() {
		Owner owner = buildOwner();
		Pet pet = buildPet("Max", 0);
		owner.addPet(pet);
		pet.setId(5);

		Visit visit = new Visit();
		visit.setDescription("Regular checkup");
		owner.addVisit(5, visit);

		assertThat(pet.getVisits()).hasSize(1);
	}

	@Test
	void testAddVisitWithInvalidPetId() {
		Owner owner = buildOwner();
		Visit visit = new Visit();
		visit.setDescription("Regular checkup");
		assertThatIllegalArgumentException().isThrownBy(() -> owner.addVisit(99, visit));
	}

	@Test
	void testToString() {
		Owner owner = buildOwner();
		String result = owner.toString();
		assertThat(result).contains("Franklin");
		assertThat(result).contains("George");
	}

}
