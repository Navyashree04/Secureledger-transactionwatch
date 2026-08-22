package com.secureledger.service;

import com.secureledger.config.JwtService;
import com.secureledger.dto.AuthResponse;
import com.secureledger.dto.LoginRequest;
import com.secureledger.dto.RegisterRequest;
import com.secureledger.entity.Role;
import com.secureledger.entity.User;
import com.secureledger.exception.InvalidCredentialsException;
import com.secureledger.exception.UserAlreadyExistsException;
import com.secureledger.repository.UserRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        AuthenticationManager authenticationManager,
                        UserDetailsService userDetailsService,
                        JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new UserAlreadyExistsException("Username '" + request.username() + "' is already taken");
        }

        // First registered user in the system becomes ADMIN for bootstrap
        // convenience in this portfolio scope; every subsequent user is
        // CUSTOMER. A real deployment would provision admins out-of-band.
        Role role = userRepository.count() == 0 ? Role.ADMIN : Role.CUSTOMER;

        User user = User.builder()
                .username(request.username())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(role)
                .build();
        userRepository.save(user);

        var userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtService.generateToken(userDetails, role.name());
        return AuthResponse.of(token, jwtService.getExpirationMs(), user.getUsername(), role.name());
    }

    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (BadCredentialsException ex) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        var userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtService.generateToken(userDetails, user.getRole().name());
        return AuthResponse.of(token, jwtService.getExpirationMs(), user.getUsername(), user.getRole().name());
    }
}
