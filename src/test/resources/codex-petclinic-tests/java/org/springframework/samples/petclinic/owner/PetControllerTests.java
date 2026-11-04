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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Test class for {@link PetController}
 */
@WebMvcTest(PetController.class)
@Import(PetTypeFormatter.class)
@DisabledInNativeImage
@DisabledInAotMode
class PetControllerTests {

	private static final int TEST_OWNER_ID = 6;

	private static final int TEST_PET_ID = 7;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OwnerRepository owners;

	@MockitoBean
	private PetTypeRepository types;

	private PetType hamster;

	@BeforeEach
	void setup() {
		hamster = new PetType();
		hamster.setId(1);
		hamster.setName("hamster");

		PetType dog = new PetType();
		dog.setId(2);
		dog.setName("dog");

		Owner owner = new Owner();
		owner.setId(TEST_OWNER_ID);
		owner.setFirstName("Jean");
		owner.setLastName("Coleman");
		owner.setAddress("105 N. Lake St.");
		owner.setCity("Monona");
		owner.setTelephone("6085552654");

		Pet pet = new Pet();
		pet.setName("Samantha");
		pet.setType(hamster);
		pet.setBirthDate(LocalDate.of(2010, 9, 7));
		owner.addPet(pet);
		pet.setId(TEST_PET_ID);

		given(this.owners.findById(TEST_OWNER_ID)).willReturn(Optional.of(owner));
		given(this.types.findPetTypes()).willReturn(List.of(hamster, dog));
	}

	@Test
	void testInitCreationForm() throws Exception {
		mockMvc.perform(get("/owners/{ownerId}/pets/new", TEST_OWNER_ID))
			.andExpect(status().isOk())
			.andExpect(view().name("pets/createOrUpdatePetForm"))
			.andExpect(model().attributeExists("pet"));
	}

	@Test
	void testProcessCreationFormSuccess() throws Exception {
		mockMvc
			.perform(post("/owners/{ownerId}/pets/new", TEST_OWNER_ID).param("name", "Betty")
				.param("type", "dog")
				.param("birthDate", "2015-02-12"))
			.andExpect(status().is3xxRedirection())
			.andExpect(view().name("redirect:/owners/{ownerId}"));
	}

	@Test
	void testProcessCreationFormWithMissingFields() throws Exception {
		mockMvc.perform(post("/owners/{ownerId}/pets/new", TEST_OWNER_ID).param("birthDate", "2015-02-12"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasErrors("pet"))
			.andExpect(view().name("pets/createOrUpdatePetForm"));
	}

	@Test
	void testProcessCreationFormWithDuplicateName() throws Exception {
		mockMvc
			.perform(post("/owners/{ownerId}/pets/new", TEST_OWNER_ID).param("name", "Samantha")
				.param("type", "hamster")
				.param("birthDate", "2015-02-12"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("pet", "name"))
			.andExpect(view().name("pets/createOrUpdatePetForm"));
	}

	@Test
	void testProcessCreationFormWithFutureBirthDate() throws Exception {
		mockMvc
			.perform(post("/owners/{ownerId}/pets/new", TEST_OWNER_ID).param("name", "Betty")
				.param("type", "dog")
				.param("birthDate", "2099-01-01"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("pet", "birthDate"))
			.andExpect(view().name("pets/createOrUpdatePetForm"));
	}

	@Test
	void testInitUpdateForm() throws Exception {
		mockMvc.perform(get("/owners/{ownerId}/pets/{petId}/edit", TEST_OWNER_ID, TEST_PET_ID))
			.andExpect(status().isOk())
			.andExpect(view().name("pets/createOrUpdatePetForm"))
			.andExpect(model().attributeExists("pet"));
	}

	@Test
	void testProcessUpdateFormSuccess() throws Exception {
		mockMvc
			.perform(post("/owners/{ownerId}/pets/{petId}/edit", TEST_OWNER_ID, TEST_PET_ID).param("name", "Samantha")
				.param("type", "hamster")
				.param("birthDate", "2010-09-07"))
			.andExpect(status().is3xxRedirection())
			.andExpect(view().name("redirect:/owners/{ownerId}"));
	}

	@Test
	void testProcessUpdateFormWithErrors() throws Exception {
		mockMvc
			.perform(post("/owners/{ownerId}/pets/{petId}/edit", TEST_OWNER_ID, TEST_PET_ID).param("name", "")
				.param("birthDate", "2010-09-07"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasErrors("pet"))
			.andExpect(view().name("pets/createOrUpdatePetForm"));
	}

	@Test
	void testProcessUpdateFormWithFutureBirthDate() throws Exception {
		mockMvc
			.perform(post("/owners/{ownerId}/pets/{petId}/edit", TEST_OWNER_ID, TEST_PET_ID).param("name", "Samantha")
				.param("type", "hamster")
				.param("birthDate", "2099-01-01"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("pet", "birthDate"))
			.andExpect(view().name("pets/createOrUpdatePetForm"));
	}

}
