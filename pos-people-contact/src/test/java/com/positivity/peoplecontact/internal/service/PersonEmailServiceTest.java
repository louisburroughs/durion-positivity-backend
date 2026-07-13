package com.positivity.peoplecontact.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import com.positivity.peoplecontact.internal.enums.ContactPointType;
import com.positivity.peoplecontact.internal.repository.PersonContactPointRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersonEmailServiceTest {

    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private PersonContactPointRepository repository;

    @InjectMocks
    private PersonEmailService service;

    private PersonContactPoint cp(String value, boolean primary) {
        return PersonContactPoint.builder()
                .personId(PERSON)
                .contactType(ContactPointType.EMAIL)
                .value(value)
                .isPrimary(primary)
                .build();
    }

    @Test
    void getEmails_returnsPrimaryAndSecondary() {
        when(repository.findByPersonIdAndContactType(PERSON, ContactPointType.EMAIL))
                .thenReturn(List.of(cp("second@x.com", false), cp("primary@x.com", true)));

        PersonEmailService.EmailPair pair = service.getEmails(PERSON);

        assertThat(pair.primary()).isEqualTo("primary@x.com");
        assertThat(pair.secondary()).isEqualTo("second@x.com");
    }

    @Test
    void replaceEmails_deletesThenSavesPrimaryAndSecondary_skippingBlanks() {
        service.replaceEmails(PERSON, "primary@x.com", "  ");

        verify(repository).deleteByPersonIdAndContactType(PERSON, ContactPointType.EMAIL);
        ArgumentCaptor<PersonContactPoint> captor = ArgumentCaptor.forClass(PersonContactPoint.class);
        verify(repository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getValue()).isEqualTo("primary@x.com");
        assertThat(captor.getValue().isPrimary()).isTrue();
    }

    @Test
    void findPersonIdsByPrimaryEmail_mapsIds() {
        when(repository.findByContactTypeAndIsPrimaryAndValueIgnoreCase(ContactPointType.EMAIL, true, "primary@x.com"))
                .thenReturn(List.of(cp("primary@x.com", true)));

        assertThat(service.findPersonIdsByPrimaryEmail("primary@x.com")).containsExactly(PERSON);
    }
}
