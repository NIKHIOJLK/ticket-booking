package com.nikhil.ticketbooking.controller;

import com.nikhil.ticketbooking.config.AppProperties;
import com.nikhil.ticketbooking.dto.LoginRequest;
import com.nikhil.ticketbooking.dto.RegisterRequest;
import com.nikhil.ticketbooking.dto.UserResponse;
import com.nikhil.ticketbooking.model.Role;
import com.nikhil.ticketbooking.model.User;
import com.nikhil.ticketbooking.repository.UserRepository;
import com.nikhil.ticketbooking.security.CurrentUser;
import com.nikhil.ticketbooking.security.JwtUtil;
import com.nikhil.ticketbooking.service.ApiException;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AppProperties props;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                          AppProperties props) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.props = props;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        String email = normalize(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(props.isAdminEmail(email) ? Role.ADMIN : Role.USER);
        user.setCreatedAt(LocalDateTime.now());
        try {
            // Returns the user without the password hash (the old version leaked it here).
            return UserResponse.of(userRepository.save(user));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("An account with this email already exists"); // lost a race to the unique index
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        String email = normalize(request.getEmail());
        User user = userRepository.findFirstByEmailIgnoreCaseOrderByIdAsc(email).orElse(null);
        if (user == null || request.getPassword() == null
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // Same message either way, so the form doesn't reveal which emails have accounts.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        // Emails listed in ADMIN_EMAILS become admins on their next login.
        if (props.isAdminEmail(user.getEmail()) && user.getRole() != Role.ADMIN) {
            user.setRole(Role.ADMIN);
            userRepository.save(user);
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        return Map.of("token", token, "user", UserResponse.of(user));
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        return userRepository.findById(CurrentUser.id(authentication))
                .map(UserResponse::of)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please log in again"));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
