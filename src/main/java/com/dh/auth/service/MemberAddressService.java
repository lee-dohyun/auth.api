package com.dh.auth.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dh.auth.config.Messages;
import com.dh.auth.dto.AddressDtos.CreateAddressRequest;
import com.dh.auth.dto.AddressDtos.UpdateAddressRequest;
import com.dh.auth.entity.Member;
import com.dh.auth.entity.MemberAddress;
import com.dh.auth.repository.MemberAddressRepository;
import com.dh.auth.repository.MemberRepository;
import com.dh.auth.security.KeycloakClient;

/** 회원 배송지 CRUD. gateway가 검증해 넘겨준 이메일을 기준으로 Member를 찾아 소유권을 확인한다. */
@Service
public class MemberAddressService {

    private final MemberAddressRepository memberAddressRepository;
    private final MemberRepository memberRepository;
    private final KeycloakClient keycloakClient;
    private final Messages messages;

    public MemberAddressService(
            MemberAddressRepository memberAddressRepository,
            MemberRepository memberRepository,
            KeycloakClient keycloakClient,
            Messages messages) {
        this.memberAddressRepository = memberAddressRepository;
        this.memberRepository = memberRepository;
        this.keycloakClient = keycloakClient;
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    public List<MemberAddress> list(String email) {
        Member member = resolveMember(email);
        return memberAddressRepository.findByMemberIdOrderByIsDefaultDescCreatedAtDesc(member.getId());
    }

    @Transactional
    public MemberAddress create(String email, CreateAddressRequest request) {
        Member member = resolveMember(email);
        boolean makeDefault = Boolean.TRUE.equals(request.isDefault())
                || memberAddressRepository.findByMemberIdAndIsDefaultTrue(member.getId()).isEmpty();

        MemberAddress address = new MemberAddress(
                member,
                request.label(),
                request.recipientName(),
                request.phoneNumber(),
                request.zipCode(),
                request.address1(),
                request.address2(),
                false);
        memberAddressRepository.save(address);

        if (makeDefault) {
            applyDefault(member.getId(), address);
        }
        return address;
    }

    @Transactional
    public MemberAddress update(String email, Long addressId, UpdateAddressRequest request) {
        MemberAddress address = resolveOwnedAddress(email, addressId);
        address.update(
                request.label(),
                request.recipientName(),
                request.phoneNumber(),
                request.zipCode(),
                request.address1(),
                request.address2());
        return address;
    }

    @Transactional
    public void delete(String email, Long addressId) {
        MemberAddress address = resolveOwnedAddress(email, addressId);
        memberAddressRepository.delete(address);
    }

    @Transactional
    public MemberAddress setDefault(String email, Long addressId) {
        MemberAddress address = resolveOwnedAddress(email, addressId);
        applyDefault(address.getMember().getId(), address);
        return address;
    }

    private void applyDefault(Long memberId, MemberAddress newDefault) {
        memberAddressRepository.findByMemberIdAndIsDefaultTrue(memberId)
                .filter(current -> !current.getId().equals(newDefault.getId()))
                .ifPresent(MemberAddress::unmarkAsDefault);
        newDefault.markAsDefault();
    }

    private MemberAddress resolveOwnedAddress(String email, Long addressId) {
        Member member = resolveMember(email);
        return memberAddressRepository.findByIdAndMemberId(addressId, member.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("address.notFound")));
    }

    private Member resolveMember(String email) {
        KeycloakClient.UserInfo user = keycloakClient.findUser(email);
        return memberRepository.findByKeycloakUserId(user.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("member.notFound")));
    }
}
