package br.com.amigavel.tarefas;

public record GamificationProgress(
        int totalXp,
        int weeklyXp,
        int monthlyXp,
        int level,
        int xpWithinLevel,
        String rewardTitle,
        int rewardTargetXp) {

    public int xpToNextLevel() {
        return 100 - xpWithinLevel;
    }

    public double levelProgress() {
        return xpWithinLevel / 100.0;
    }
}
