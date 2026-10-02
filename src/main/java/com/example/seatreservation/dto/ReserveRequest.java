package com.example.seatreservation.dto;

import javax.validation.constraints.NotEmpty;
import java.util.List;

public record ReserveRequest(@NotEmpty List<String> seats) {}
