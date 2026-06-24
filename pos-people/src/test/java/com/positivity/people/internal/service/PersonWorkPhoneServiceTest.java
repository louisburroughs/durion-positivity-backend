package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.PersonContactPoint;
import com.positivity.people.internal.enums.ContactPointType;
import com.positivity.people.internal.repository.PersonContactPointRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersonWorkPhoneServiceTest {

    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private PersonContactPointRepository repository;

    @InjectMocks
    private PersonWorkPhoneService service;

    private PersonContactPoint cp(String value, boolean primary) {
        return PersonContactPoint.builder()
                .personId(PERSON)
                .contactType(ContactPointType.PHONE_WORK)
                .value(value)
                .isPrimary(primary)
                .build();
    }

    @Test
    void getWorkPhones_returnsValuesPrimaryFirst() {
        when(repository.findByPersonIdAndContactType(PERSON, ContactPointType.PHONE_WORK))
                .thenReturn(List.of(cp("555-0002", false), cp("555-0001", true)));

        assertThat(service.getWorkPhones(PERSON)).containsExactly("555-0001", "555-0002");
    }

    @Test
    void replaceWorkPhones_deletesThenInsertsFirstPrimary_skippingBlanks() {
        service.replaceWorkPhones(PERSON, List.of("555-0001", "  ", "555-0002"));

        verify(repository).deleteByPersonIdAndContactType(PERSON, ContactPointType.PHONE_WORK);
        ArgumentCaptor<PersonContactPoint> captor = ArgumentCaptor.forClass(PersonContactPoint.class);
        verify(repository, times(2)).save(captor.capture());
        List<PersonContactPoint> saved = captor.getAllValues();
        assertThat(saved).extracting(PersonContactPoint::getValue).containsExactly("555-0001", "555-0002");
        assertThat(saved.get(0).isPrimary()).isTrue();
        assertThat(saved.get(1).isPrimary()).isFalse();
    }

    @Test
    void findPersonIdsByWorkPhone_mapsToPersonIds() {
        when(repository.findByContactTypeAndValue(ContactPointType.PHONE_WORK, "555-0001"))
                .thenReturn(List.of(cp("555-0001", true)));

        assertThat(service.findPersonIdsByWorkPhone("555-0001")).containsExactly(PERSON);
    }
}
