// What is this file?
// This controller manages the Dispatcher panel: list drivers, select a driver, view stats and logs.
// Why is this needed?
// It connects the database (Driver, Event entities) with the Dispatcher front-end.

package com.drivermonitoring.controller;

import com.drivermonitoring.model.Driver;
import com.drivermonitoring.model.Event;
import com.drivermonitoring.model.DriverSession;
import com.drivermonitoring.repository.DriverRepository;
import com.drivermonitoring.repository.EventRepository;
import com.drivermonitoring.service.DriverAnalyticsService;
import com.drivermonitoring.service.DriverRatingService;
import com.drivermonitoring.service.SessionService;
import com.drivermonitoring.ai.FatiguePrediction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@Controller
public class DispatcherController {

    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DriverAnalyticsService driverAnalyticsService;

    @Autowired
    private DriverRatingService driverRatingService;

    @Autowired
    private SessionService sessionService;

    @GetMapping("/dispatcher")
    public String dispatcherPanel(Model model) {
        List<Driver> drivers = driverRepository.findAll();
        // Формируем список с оценкой
        List<HashMap<String, String>> driverInfos = new ArrayList<>();
        for (Driver driver : drivers) {
            HashMap<String, String> info = new HashMap<>();
            info.put("driverName", driver.getDriverName());
            info.put("driverId", driver.getDriverId());
            info.put("rating", driverRatingService.getDriverRating(driver.getDriverId()));
            driverInfos.add(info);
        }
        model.addAttribute("driverInfos", driverInfos);
        return "dispatcher_panel";
    }

    @GetMapping("/dispatcher/driver/{driverId}")
    public String driverStats(@PathVariable String driverId, Model model) {
        Driver driver = driverRepository.findById(driverId).orElse(null);
        List<DriverSession> sessions = sessionService.getSessionsForDriver(driverId);
        float totalDuration = 0f;
        float drowsyTime = 0f;
        float distractedTime = 0f;
        float normalTime = 0f;
        float sumEar = 0f;
        int earCount = 0;
        float sumBlinkRate = 0f;
        int blinkCount = 0;
        int sessionCount = 0;
        for (DriverSession session : sessions) {
            if (session.getStartTime() == null || session.getEndTime() == null) continue;
            sessionCount++;
            float sessionDuration = java.time.Duration.between(session.getStartTime(), session.getEndTime()).getSeconds();
            totalDuration += sessionDuration;
            List<Event> events = eventRepository.findBySessionId(session.getSessionId());
            events.sort(java.util.Comparator.comparing(Event::getStartTime));
            // --- Новый алгоритм: собираем интервалы событий ---
            List<Interval> drowsyIntervals = new ArrayList<>();
            List<Interval> distractedIntervals = new ArrayList<>();
            for (Event e : events) {
                if (e.getStartTime() == null || e.getEndTime() == null) continue;
                if ("DROWSY".equalsIgnoreCase(e.getEventType())) {
                    drowsyIntervals.add(new Interval(e.getStartTime(), e.getEndTime()));
                } else if ("DISTRACTED".equalsIgnoreCase(e.getEventType())) {
                    distractedIntervals.add(new Interval(e.getStartTime(), e.getEndTime()));
                }
                // Для статистики по EAR и blinkRate
                if (e.getEarValue() != null) {
                    sumEar += e.getEarValue();
                    earCount++;
                }
                if (e.getMetadata() != null && e.getMetadata().contains("blinkRate")) {
                    try {
                        String meta = e.getMetadata();
                        int idx = meta.indexOf("blinkRate");
                        if (idx >= 0) {
                            String sub = meta.substring(idx);
                            String[] parts = sub.split(":");
                            if (parts.length > 1) {
                                String val = parts[1].replaceAll("[^0-9.]", "");
                                sumBlinkRate += Float.parseFloat(val);
                                blinkCount++;
                            }
                        }
                    } catch (Exception ignore) {}
                }
            }
            // Объединяем перекрывающиеся интервалы
            List<Interval> mergedDrowsy = mergeIntervals(drowsyIntervals);
            List<Interval> mergedDistracted = mergeIntervals(distractedIntervals);
            // Суммируем длительности
            float sessionDrowsy = 0f;
            for (Interval i : mergedDrowsy) sessionDrowsy += i.durationSeconds();
            float sessionDistracted = 0f;
            for (Interval i : mergedDistracted) sessionDistracted += i.durationSeconds();
            // "Занятые" интервалы (drowsy+distracted) для расчёта нормального времени
            List<Interval> allBusy = new ArrayList<>();
            allBusy.addAll(mergedDrowsy);
            allBusy.addAll(mergedDistracted);
            List<Interval> mergedBusy = mergeIntervals(allBusy);
            float busyTime = 0f;
            for (Interval i : mergedBusy) busyTime += i.durationSeconds();
            float sessionNormal = sessionDuration - busyTime;
            // Если событий не было — вся сессия "нормальная"
            if (events.isEmpty()) sessionNormal = sessionDuration;
            drowsyTime += sessionDrowsy;
            distractedTime += sessionDistracted;
            normalTime += sessionNormal;
        }
        float drowsyPercent = totalDuration > 0 ? drowsyTime / totalDuration * 100f : 0f;
        float distractedPercent = totalDuration > 0 ? distractedTime / totalDuration * 100f : 0f;
        float normalPercent = totalDuration > 0 ? normalTime / totalDuration * 100f : 0f;
        float avgEar = earCount > 0 ? sumEar / earCount : 0f;
        float avgBlinkRate = blinkCount > 0 ? sumBlinkRate / blinkCount : 0f;
        float avgSessionDuration = sessionCount > 0 ? totalDuration / sessionCount : 0f;
        // Округляем проценты и средние значения до 1 знака после запятой
        drowsyPercent = Math.round(drowsyPercent * 10f) / 10f;
        distractedPercent = Math.round(distractedPercent * 10f) / 10f;
        normalPercent = Math.round(normalPercent * 10f) / 10f;
        avgEar = Math.round(avgEar * 100f) / 100f; // EAR обычно показывают с двумя знаками
        avgBlinkRate = Math.round(avgBlinkRate * 10f) / 10f;
        avgSessionDuration = Math.round(avgSessionDuration * 10f) / 10f;
        model.addAttribute("driver", driver);
        model.addAttribute("drowsyPercent", drowsyPercent);
        model.addAttribute("distractedPercent", distractedPercent);
        model.addAttribute("normalPercent", normalPercent);
        model.addAttribute("avgEar", avgEar);
        model.addAttribute("avgBlinkRate", avgBlinkRate);
        model.addAttribute("sessionCount", sessionCount);
        model.addAttribute("avgSessionDuration", avgSessionDuration);

        // --- Динамика по сессии для графика ---
        // Динамический выбор количества интервалов: минимум 5, максимум 30, но не больше половины секунд сессии
        int bins = 20;
        if (sessionCount > 0 && totalDuration > 0) {
            float avgSessionSec = totalDuration / sessionCount;
            bins = Math.max(5, Math.min(30, (int)Math.round(avgSessionSec / 2)));
        }
        float[] drowsyBins = new float[bins];
        float[] distractedBins = new float[bins];
        int[] binCounts = new int[bins];
        for (DriverSession session : sessions) {
            if (session.getStartTime() == null || session.getEndTime() == null) continue;
            float sessionDuration = java.time.Duration.between(session.getStartTime(), session.getEndTime()).getSeconds();
            if (sessionDuration < 1) continue;
            int sessionBins = Math.max(5, Math.min(30, (int)Math.round(sessionDuration / 2)));
            for (int i = 0; i < sessionBins; i++) {
                float binStart = i * sessionDuration / sessionBins;
                float binEnd = (i + 1) * sessionDuration / sessionBins;
                float drowsyTimeBin = 0f, distractedTimeBin = 0f;
                List<Event> events = eventRepository.findBySessionId(session.getSessionId());
                for (Event e : events) {
                    if (e.getStartTime() == null || e.getEndTime() == null) continue;
                    float eventStart = java.time.Duration.between(session.getStartTime(), e.getStartTime()).getSeconds();
                    float eventEnd = java.time.Duration.between(session.getStartTime(), e.getEndTime()).getSeconds();
                    float overlap = Math.max(0, Math.min(binEnd, eventEnd) - Math.max(binStart, eventStart));
                    if (overlap > 0) {
                        if ("DROWSY".equalsIgnoreCase(e.getEventType())) drowsyTimeBin += overlap;
                        else if ("DISTRACTED".equalsIgnoreCase(e.getEventType())) distractedTimeBin += overlap;
                    }
                }
                // Пропорционально распределяем по глобальным bins
                int globalBin = Math.min((int)Math.floor((float)i / sessionBins * bins), bins - 1);
                drowsyBins[globalBin] += drowsyTimeBin / (binEnd - binStart);
                distractedBins[globalBin] += distractedTimeBin / (binEnd - binStart);
                binCounts[globalBin]++;
            }
        }
        float[] drowsyAvg = new float[bins];
        float[] distractedAvg = new float[bins];
        for (int i = 0; i < bins; i++) {
            drowsyAvg[i] = binCounts[i] > 0 ? Math.min(1f, drowsyBins[i] / binCounts[i]) : 0f;
            distractedAvg[i] = binCounts[i] > 0 ? Math.min(1f, distractedBins[i] / binCounts[i]) : 0f;
        }
        model.addAttribute("drowsyAvgBins", drowsyAvg);
        model.addAttribute("distractedAvgBins", distractedAvg);
        model.addAttribute("binsCount", bins);

        // --- Динамическая оценка и сравнение с другими водителями ---
        // Получаем всех водителей для сравнения
        List<Driver> allDrivers = driverRepository.findAll();
        List<Float> allNormalPercents = new ArrayList<>();
        for (Driver d : allDrivers) {
            if (d.getDriverId().equals(driverId)) continue;
            List<DriverSession> dsessions = sessionService.getSessionsForDriver(d.getDriverId());
            float dTotal = 0f, dNormal = 0f; //, dDrowsy = 0f, dDistracted = 0f раскоментировать когда понадобятся
            for (DriverSession s : dsessions) {
                if (s.getStartTime() == null || s.getEndTime() == null) continue;
                float sDuration = java.time.Duration.between(s.getStartTime(), s.getEndTime()).getSeconds();
                dTotal += sDuration;
                List<Event> es = eventRepository.findBySessionId(s.getSessionId());
                List<Interval> drowsyI = new ArrayList<>();
                List<Interval> distractedI = new ArrayList<>();
                for (Event e : es) {
                    if (e.getStartTime() == null || e.getEndTime() == null) continue;
                    if ("DROWSY".equalsIgnoreCase(e.getEventType())) drowsyI.add(new Interval(e.getStartTime(), e.getEndTime()));
                    else if ("DISTRACTED".equalsIgnoreCase(e.getEventType())) distractedI.add(new Interval(e.getStartTime(), e.getEndTime()));
                }
                List<Interval> mergedD = mergeIntervals(drowsyI);
                List<Interval> mergedT = mergeIntervals(distractedI);
                List<Interval> allBusy = new ArrayList<>();
                allBusy.addAll(mergedD); allBusy.addAll(mergedT);
                List<Interval> mergedBusy = mergeIntervals(allBusy);
                float busy = 0f;
                for (Interval i : mergedBusy) busy += i.durationSeconds();
                float sNormal = sDuration - busy;
                if (es.isEmpty()) sNormal = sDuration;
                dNormal += sNormal;
            }
            float dNormalPercent = dTotal > 0 ? dNormal / dTotal * 100f : 0f;
            allNormalPercents.add(dNormalPercent);
        }
        final float finalNormalPercent = normalPercent; // Для использования в лямбде ниже
        long worseCount = allNormalPercents.stream().filter(p -> p < finalNormalPercent).count();
        int totalOther = allNormalPercents.size();
        int betterThanPercent = totalOther > 0 ? Math.round(worseCount * 100f / totalOther) : 100;
        // Формируем динамическую фразу и совет
        String summary, recommendation, comparison;
        String summaryClass;
        if (normalPercent > 90) {
            summary = "Водитель демонстрирует высокий уровень внимания.";
            recommendation = "Рекомендация: продолжать мониторинг в стандартном режиме.";
            summaryClass = "text-success";
        } else if (normalPercent > 75) {
            summary = "Внимательность водителя на среднем уровне.";
            recommendation = "Рекомендация: периодически напоминать о необходимости отдыха.";
            summaryClass = "text-warning";
        } else if (normalPercent > 60) {
            summary = "Водитель часто отвлекается или проявляет признаки усталости.";
            recommendation = "Рекомендация: обратить внимание, возможен риск инцидента.";
            summaryClass = "text-warning";
        } else {
            summary = "Водитель входит в группу риска.";
            recommendation = "Рекомендация: рекомендуется обсудить с водителем режим работы и отдыха.";
            summaryClass = "text-danger";
        }
        comparison = "Показатель времени <b>в норме</b> выше, чем у " + betterThanPercent + "% водителей.";
        model.addAttribute("driverSummary", summary);
        model.addAttribute("driverRecommendation", recommendation);
        model.addAttribute("driverComparison", comparison);
        model.addAttribute("driverSummaryClass", summaryClass);

        return "driver_statistics";
    }

    // --- Вспомогательный класс и методы для работы с интервалами ---
    private static class Interval {
        private final java.time.LocalDateTime start;
        private final java.time.LocalDateTime end;
        Interval(java.time.LocalDateTime start, java.time.LocalDateTime end) {
            this.start = start;
            this.end = end;
        }
        float durationSeconds() {
            return (float) java.time.Duration.between(start, end).getSeconds();
        }
    }
    private static List<Interval> mergeIntervals(List<Interval> intervals) {
        if (intervals.isEmpty()) return intervals;
        intervals.sort(java.util.Comparator.comparing(i -> i.start));
        List<Interval> merged = new ArrayList<>();
        Interval prev = intervals.get(0);
        for (int i = 1; i < intervals.size(); i++) {
            Interval curr = intervals.get(i);
            if (!curr.start.isAfter(prev.end)) { // Пересекаются или соприкасаются
                prev = new Interval(prev.start, curr.end.isAfter(prev.end) ? curr.end : prev.end);
            } else {
                merged.add(prev);
                prev = curr;
            }
        }
        merged.add(prev);
        return merged;
    }

    @GetMapping("/dispatcher/driver/{driverId}/prediction")
    public String driverPrediction(@PathVariable String driverId, Model model) {
        FatiguePrediction prediction = driverAnalyticsService.getFatiguePrediction(driverId);
        model.addAttribute("prediction", prediction);
        model.addAttribute("driverId", driverId);
        return "driver_prediction";
    }
}

@RestController
@RequestMapping("/api")
class DispatcherApiController {
    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    public DispatcherApiController(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }
    @SuppressWarnings("unchecked")
    @GetMapping(value = "/label-dataset", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getLabelDataset() {
        var labelEvents = eventRepository.findAll().stream()
                .filter(e -> "LABEL".equalsIgnoreCase(e.getEventType()))
                .map(e -> {
                    try {
                        Map<String, Object> meta = objectMapper.readValue(e.getMetadata(), Map.class);
                        Map<String, Object> features = (Map<String, Object>) meta.get("features");
                        // Новый блок: добавляем context, если есть
                        Object context = meta.get("context");
                        Map<String, Object> result = new HashMap<>();
                        result.put("driverId", e.getDriverId());
                        result.put("sessionId", e.getSessionId());
                        result.put("label", meta.get("label"));
                        result.put("timestamp", meta.get("label_timestamp"));
                        result.put("features", features);
                        if (context != null) {
                            result.put("context", context);
                        }
                        return result;
                    } catch (Exception ex) {
                        return null;
                    }
                })
                .filter(e -> e != null)
                .collect(Collectors.toList());
        return ResponseEntity.ok(labelEvents);
    }
}
