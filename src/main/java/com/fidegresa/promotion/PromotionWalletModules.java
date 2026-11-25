package com.fidegresa.promotion;

import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.repository.PromotionRepository;
import com.fidegresa.wallet.WalletModuleSource;
import com.fidegresa.wallet.WalletPassBuilder.Module;
import org.springframework.stereotype.Component;

import java.util.List;

/** Las promociones vigentes del cliente se muestran en el reverso de su pase de Google Wallet. */
@Component
class PromotionWalletModules implements WalletModuleSource {
    private final PromotionRepository promotions;

    PromotionWalletModules(PromotionRepository promotions) {
        this.promotions = promotions;
    }

    @Override
    public List<Module> modulesFor(LoyaltyCard card) {
        return promotions.findLiveFor(card.getCustomer().getId(), card.getProgram().getId(), PromotionService.today()).stream()
                .map(p -> new Module("promo_" + p.getId(), p.getName(), PromotionService.walletText(p)))
                .toList();
    }
}
