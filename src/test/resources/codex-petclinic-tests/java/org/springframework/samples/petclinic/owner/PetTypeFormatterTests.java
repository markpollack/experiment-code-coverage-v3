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

import java.text.ParseException;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Test class for {@link PetTypeFormatter}
 */
class PetTypeFormatterTests {

	@Test
	void testPrint() {
		PetTypeRepository mockRepo = mock(PetTypeRepository.class);
		PetTypeFormatter formatter = new PetTypeFormatter(mockRepo);
		PetType petType = new PetType();
		petType.setName("cat");
		assertThat(formatter.print(petType, Locale.ENGLISH)).isEqualTo("cat");
	}

	@Test
	void testPrintWithNullName() {
		PetTypeRepository mockRepo = mock(PetTypeRepository.class);
		PetTypeFormatter formatter = new PetTypeFormatter(mockRepo);
		PetType petType = new PetType();
		assertThat(formatter.print(petType, Locale.ENGLISH)).isEqualTo("<null>");
	}

	@Test
	void testParse() throws ParseException {
		PetTypeRepository mockRepo = mock(PetTypeRepository.class);
		PetType dog = new PetType();
		dog.setName("dog");
		given(mockRepo.findPetTypes()).willReturn(List.of(dog));
		PetTypeFormatter formatter = new PetTypeFormatter(mockRepo);
		PetType result = formatter.parse("dog", Locale.ENGLISH);
		assertThat(result).isEqualTo(dog);
	}

	@Test
	void testParseNotFound() {
		PetTypeRepository mockRepo = mock(PetTypeRepository.class);
		given(mockRepo.findPetTypes()).willReturn(List.of());
		PetTypeFormatter formatter = new PetTypeFormatter(mockRepo);
		assertThatThrownBy(() -> formatter.parse("unknown", Locale.ENGLISH)).isInstanceOf(ParseException.class);
	}

}
