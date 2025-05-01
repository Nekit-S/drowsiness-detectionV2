// File: src/main/java/com/drivermonitoring/service/EventLoggingServiceImpl.java
// What is this file?
// Implements the logic for saving driver events to the database with session context.
// Why is this needed?
// It provides a clean, reusable way to store monitoring data with rich contextual information.

package com.drivermonitoring.service;

import com.drivermonitoring.model.DriverSession;
import com.drivermonitoring.model.DriverState;
import com.drivermonitoring.model.Event;
import com.drivermonitoring.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class EventLoggingServiceImpl implements EventLoggingService {

    private static final Logger logger = LoggerFactory.getLogger(EventLoggingServiceImpl.class);

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SessionService sessionService; // Assuming SessionService exists and provides getActiveSession

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public Event logEvent(String driverId, DriverState driverState, float duration) {
        // Validate input parameters
        if (driverId == null || driverState == null) {
            logger.error("Invalid parameters: driverId or driverState is null");
            return null;
        }

        // Skip logging for NORMAL state
        if (driverState == DriverState.NORMAL) {
            logger.debug("Skipping NORMAL state event logging for driver {}", driverId);
            return null;
        }

        try {
            // Get active session for the driver
            DriverSession session = sessionService.getActiveSession(driverId);
            if (session == null) {
                // Cannot log event without an active session
                logger.warn("Cannot log event: No active session for driver {}", driverId);
                return null;
            }

            Event event = new Event(
                session.getSessionId(),
                driverId,
                driverState.name(), // Use enum name as eventType string
                duration
            );

            Event savedEvent = eventRepository.save(event);
            logger.info("Logged {} event for driver {}, duration: {}s, session: {}",
                        driverState, driverId, duration, session.getSessionId());

            return savedEvent;
        } catch (Exception e) {
            logger.error("Error logging event for driver {}: {}", driverId, e.getMessage(), e);
            return null;
        }
    }

    @Override
    @Transactional
    public Event logEventWithMetadata(String driverId, DriverState driverState, float duration, Map<String, Object> metadata) {
        // Validate input parameters
        if (driverId == null || driverState == null) {
            logger.error("Invalid parameters: driverId or driverState is null");
            return null;
        }

        // Skip logging for NORMAL state
        if (driverState == DriverState.NORMAL) {
             logger.debug("Skipping NORMAL state event logging with metadata for driver {}", driverId);
            return null;
        }

        try {
            // Get active session for the driver
            DriverSession session = sessionService.getActiveSession(driverId);
            if (session == null) {
                logger.warn("Cannot log event with metadata: No active session for driver {}", driverId);
                return null;
            }

            // Ensure metadata is not null and create a mutable copy
            Map<String, Object> safeMetadata = (metadata != null) ? new HashMap<>(metadata) : new HashMap<>();

            // Извлекаем ключевые признаки для отдельных полей
            Float earValue = safeMetadata.containsKey("earValue") ? parseFloatSafe(safeMetadata.get("earValue")) : null;
            Boolean faceDetected = safeMetadata.containsKey("faceDetected") ? parseBooleanSafe(safeMetadata.get("faceDetected")) : null;

            // Удаляем эти признаки из JSON, чтобы не дублировать
            safeMetadata.remove("earValue");
            safeMetadata.remove("faceDetected");

            // Add timestamp if not present
            safeMetadata.putIfAbsent("timestamp", System.currentTimeMillis());
            // Add event context (can be useful for analysis)
            safeMetadata.putIfAbsent("sessionId", session.getSessionId());
            safeMetadata.putIfAbsent("eventType", driverState.name());
            safeMetadata.putIfAbsent("source", "MediaPipe");

            // Convert metadata map to JSON string
            String metadataJson;
            try {
                metadataJson = objectMapper.writeValueAsString(safeMetadata);
            } catch (JsonProcessingException e) {
                logger.error("Failed to convert metadata to JSON for driver {}: {}", driverId, e.getMessage());
                metadataJson = "{}";
            }

            // Создаем Event с новыми полями
            Event event = new Event(
                session.getSessionId(),
                driverId,
                driverState.name(),
                duration,
                metadataJson
            );
            event.setEarValue(earValue);
            event.setFaceDetected(faceDetected);

            Event savedEvent = eventRepository.save(event);
            logger.info("Logged {} event with metadata from {} for driver {}, duration: {}s, session: {}",
                    driverState, safeMetadata.getOrDefault("source", "unknown"), driverId, duration, session.getSessionId());

            return savedEvent;
        } catch (Exception e) {
            logger.error("Error logging event with metadata for driver {}: {}", driverId, e.getMessage(), e);
            return null;
        }
    }

    @Override
    @Transactional
    public void logLabeledEvent(com.drivermonitoring.dto.LabelEventDTO labelEvent) {
        if (labelEvent == null || labelEvent.getDriverId() == null || labelEvent.getLabel() == null) {
            logger.warn("Invalid labeled event: {}", labelEvent);
            return;
        }
        try {
            // Получаем активную сессию
            DriverSession session = sessionService.getActiveSession(labelEvent.getDriverId());
            if (session == null) {
                logger.warn("Cannot log labeled event: No active session for driver {}", labelEvent.getDriverId());
                return;
            }
            // Сохраняем все признаки и метку в metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("features", labelEvent.getFeatures());
            metadata.put("is_labeled", true);
            metadata.put("label", labelEvent.getLabel());
            metadata.put("label_timestamp", labelEvent.getTimestamp());
            metadata.put("sessionId", labelEvent.getSessionId());
            // context больше не сохраняем
            // Сохраняем eventType = LABEL, duration = 0
            String metadataJson = objectMapper.writeValueAsString(metadata);
            Event event = new Event(
                session.getSessionId(),
                labelEvent.getDriverId(),
                "LABEL", // eventType
                0f,
                metadataJson
            );
            eventRepository.save(event);
            logger.info("Labeled event saved for driver {}: label={}, session={}", labelEvent.getDriverId(), labelEvent.getLabel(), session.getSessionId());
        } catch (Exception e) {
            logger.error("Error logging labeled event for driver {}: {}", labelEvent.getDriverId(), e.getMessage(), e);
        }
    }

    @Override
    public List<Event> getEventsForSession(Long sessionId) {
        if (sessionId == null) {
            logger.warn("Cannot get events: sessionId is null");
            return Collections.emptyList();
        }

        try {
            return eventRepository.findBySessionId(sessionId);
        } catch (Exception e) {
            logger.error("Error retrieving events for session {}: {}", sessionId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<Event> getRecentEventsForDriver(String driverId, int limit) {
        if (driverId == null) {
            logger.warn("Cannot get recent events: driverId is null");
            return Collections.emptyList();
        }
        if (limit <= 0) {
             logger.warn("Invalid limit specified for getRecentEventsForDriver: {}", limit);
             return Collections.emptyList();
        }

        try {
            // Assuming EventRepository has findByDriverIdOrderByStartTimeDesc method
            return eventRepository.findByDriverIdOrderByStartTimeDesc(driverId)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error retrieving recent events for driver {}: {}", driverId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // Вспомогательные методы для безопасного парсинга
    private Float parseFloatSafe(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof Number) return ((Number) value).floatValue();
            return Float.parseFloat(value.toString());
        } catch (Exception e) {
            return null;
        }
    }
    private Boolean parseBooleanSafe(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }
}
