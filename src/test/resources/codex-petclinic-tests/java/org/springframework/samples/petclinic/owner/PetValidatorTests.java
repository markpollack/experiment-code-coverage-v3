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
import org.springframework.validation.BeanPropertyBindingResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for {@link PetValidator}
 */
class PetValidatorTests {

	private final PetValidator validator = new PetValidator();

	@Test
	void testSupportsReturnsTrueForPet() {
		assertThat(validator.supports(Pet.class)).isTrue();
	}

	@Test
	void testSupportsReturnsFalseForOtherClass() {
		assertThat(validator.supports(Object.class)).isFalse();
	}

	@Test
	void testValidateWithMissingName() {
		Pet pet = new Pet();
		pet.setBirthDate(LocalDate.now());
		BeanPropertyBindingResult errors = new BeanPropertyBindingResult(pet, "pet");
		validator.validate(pet, errors);
		assertThat(errors.hasFieldErrors("name")).isTrue();
	}

	@Test
	void testValidateNewPetWithNoType() {
		Pet pet = new Pet();
		pet.setName("Buddy");
		pet.setBirthDate(LocalDate.now());
		BeanPropertyBindingResult errors = new BeanPropertyBindingResult(pet, "pet");
		validator.validate(pet, errors);
		assertThat(errors.hasFieldErrors("type")).isTrue();
	}

	@Test
	void testValidateExistingPetWithNoTypePasses() {
		Pet pet = new Pet();
		pet.setId(1);
		pet.setName("Buddy");
		pet.setBirthDate(LocalDate.now());
		BeanPropertyBindingResult errors = new BeanPropertyBindingResult(pet, "pet");
		validator.validate(pet, errors);
		assertThat(errors.hasFieldErrors("type")).isFalse();
	}

	@Test
	void testValidateWithMissingBirthDate() {
		Pet pet = new Pet();
		pet.setName("Buddy");
		PetType dog = new PetType();
		dog.setName("dog");
		pet.setType(dog);
		BeanPropertyBindingResult errors = new BeanPropertyBindingResult(pet, "pet");
		validator.validate(pet, errors);
		assertThat(errors.hasFieldErrors("birthDate")).isTrue();
	}

	@Test
	void testValidateValidPet() {
		Pet pet = new Pet();
		pet.setId(1);
		pet.setName("Buddy");
		PetType dog = new PetType();
		dog.setName("dog");
		pet.setType(dog);
		pet.setBirthDate(LocalDate.now());
		BeanPropertyBindingResult errors = new BeanPropertyBindingResult(pet, "pet");
		validator.validate(pet, errors);
		assertThat(errors.hasErrors()).isFalse();
	}

}
