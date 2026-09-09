package com.commerceflow.authservice.mapper;

import java.util.Set;
import java.util.stream.Collectors;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import com.commerceflow.authservice.dto.UserResponse;
import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;

/** Maps the {@link User} aggregate onto its public projection. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserMapper {

    @Mapping(target = "roles", source = "roles", qualifiedByName = "rolesToNames")
    UserResponse toResponse(User user);

    @Named("rolesToNames")
    default Set<String> rolesToNames(Set<Role> roles) {
        if (roles == null) {
            return Set.of();
        }
        return roles.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
